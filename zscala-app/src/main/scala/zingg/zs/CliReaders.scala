package zingg.zs

import mainargs.TokensReader

/** Custom [[mainargs.TokensReader]] instances so the `@main` method can take
  * its arguments at their final, parsed type — [[Phase]], [[os.Path]], etc.
  * — without any later string-decoding step.
  */
object CliReaders {

  implicit object PhaseRead extends TokensReader.Simple[Phase] {
    def shortName            = "phase"
    def read(s: Seq[String]) = Phase.parse(s.last)
  }

  /** Paths are resolved against the process cwd, exactly like the shell would. */
  implicit object PathRead extends TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(s: Seq[String]) =
      try Right(os.Path(s.last, os.pwd))
      catch { case e: IllegalArgumentException => Left(e.getMessage) }
  }

  implicit object EmailRead extends TokensReader.Simple[Email] {
    def shortName = "email"
    def read(s: Seq[String]) = Email.parse(s.last)
  }
}

/** A syntactically-valid email address. The CLI accepts only well-formed
  * addresses; downstream code never has to revalidate.
  */
final case class Email(value: String) extends AnyVal

object Email {
  private val pattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r
  def parse(s: String): Either[String, Email] =
    if (pattern.matches(s)) Right(Email(s))
    else Left(s"'$s' is not a valid email address")
}
