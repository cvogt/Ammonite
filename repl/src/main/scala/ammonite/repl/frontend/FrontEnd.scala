package ammonite.repl.frontend

import java.io.{OutputStreamWriter, OutputStream, InputStream}

import ammonite.repl._
import fastparse.core.Result
import jline.console.{completer, ConsoleReader}
import acyclic.file

import scala.annotation.tailrec
import scala.tools.nsc.interpreter
import ammonite.terminal._
import ammonite.terminal.LazyList._

import scala.tools.nsc.interpreter.JList
import scalaparse.Scala._
import scalaparse.syntax.Identifiers._

/**
 * All the mucky JLine interfacing code
 */
trait FrontEnd{
  def width: Int
  def height: Int
  def action(input: InputStream,
             reader: java.io.Reader,
             output: OutputStream,
             prompt: String,
             colors: Colors,
             compilerComplete: (Int, String) => (Int, Seq[String], Seq[String]),
             history: Seq[String],
             addHistory: String => Unit): Res[(String, Seq[String])]
}

object FrontEnd{
  object JLineUnix extends JLineTerm(() => new jline.UnixTerminal())
  object JLineWindows extends JLineTerm(() => new jline.WindowsTerminal())
  class JLineTerm(makeTerm: () => jline.Terminal) extends FrontEnd{
    def width = makeTerm().getWidth
    def height = makeTerm().getHeight

    def action(input: InputStream,
               reader: java.io.Reader,
               output: OutputStream,
               prompt: String,
               colors: Colors,
               compilerComplete: (Int, String) => (Int, Seq[String], Seq[String]),
               history: Seq[String],
               addHistory: String => Unit) = {

      val term = makeTerm()
      term.init()
      val reader = new ConsoleReader(input, output, term)
      reader.setHistoryEnabled(true)
      var signatures = Seq.empty[String]
      reader.addCompleter(new jline.console.completer.Completer {

        def complete(_buf: String, cursor: Int, candidates: JList[CharSequence]): Int = {
          val buf = if (_buf == null) "" else _buf
          import collection.JavaConversions._
          val (completionBase, completions, sigs) = compilerComplete(
            cursor,
            buf
          )
          if (!completions.isEmpty) {
            candidates.addAll(completions.sorted)
            signatures = sigs.sorted
          } else if (!sigs.isEmpty){
            reader.println()
            sigs.foreach(reader.println)
            reader.drawLine()
          }

          completionBase
        }
      })
      reader.setExpandEvents(false)
      reader.setHandleUserInterrupt(true)
      val defaultHandler = reader.getCompletionHandler
      reader.setCompletionHandler(new completer.CompletionHandler {
        def complete(reader: ConsoleReader, candidates: JList[CharSequence], position: Int) = {
          if (!signatures.isEmpty){
            reader.println()
            signatures.foreach(reader.println)
            reader.drawLine()
          }
          defaultHandler.complete(reader, candidates, position)
        }
      })

      history.foreach(reader.getHistory.add)

      @tailrec def readCode(buffered: String): Res[(String, Seq[String])] = {
        Option(reader.readLine(
          if (buffered.isEmpty) prompt
          // Strip ANSI color codes, as described http://stackoverflow.com/a/14652763/871202
          else " " * prompt.replaceAll("\u001B\\[[;\\d]*m", "").length
        )) match {
          case None => Res.Exit(())
          case Some(newCode) =>
            val code = buffered + newCode
            Parsers.split(code) match{
              case Some(Result.Success(value, idx)) =>
                addHistory(code)
                Res.Success(code -> value)
              case Some(f: Result.Failure) =>
                addHistory(code)
                Res.Failure(fastparse.core.SyntaxError.msg(f.input, f.traced.expected, f.index))
              case None => readCode(code + "\n")
            }
        }
      }


      try for {
        _ <- Catching{ case e: jline.console.UserInterruptException =>
          if (e.getPartialLine == "") reader.println("Ctrl-D to exit")
          Res.Skip
        }
        res <- readCode("")
      } yield res
      finally term.restore()
    }
  }
}
