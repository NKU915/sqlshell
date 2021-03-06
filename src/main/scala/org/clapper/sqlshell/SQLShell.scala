/*
  ---------------------------------------------------------------------------
  This software is released under a BSD license, adapted from
  http://opensource.org/licenses/bsd-license.php

  Copyright (c) 2009-2011, Brian M. Clapper
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are
  met:

   * Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

   * Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

   * Neither the names "clapper.org", "SQLShell", nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
  IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  ---------------------------------------------------------------------------
 */

package org.clapper.sqlshell

import org.clapper.sqlshell.log.{logger, Verbose}

import grizzled.cmd._
import grizzled.readline.{ListCompleter,
                          PathnameCompleter,
                          CompletionToken,
                          CompleterHelper,
                          LineToken,
                          Delim,
                          Cursor}
import grizzled.readline.Readline
import grizzled.readline.Readline.ReadlineType
import grizzled.readline.Readline.ReadlineType._
import grizzled.string.GrizzledString._
import grizzled.string.WordWrapper
import grizzled.config.Configuration
import grizzled.math.util._
import grizzled.util.withCloseable

import java.sql.{Connection,
                 DatabaseMetaData,
                 ResultSet,
                 ResultSetMetaData,
                 Statement,
                 SQLException,
                 Types => SQLTypes}
import java.io.File
import java.util.Date
import java.text.SimpleDateFormat

import scala.collection.mutable.{ArrayBuffer,
                                 LinkedHashMap,
                                 ListBuffer,
                                 Map => MutableMap,
                                 Set => MutableSet}
import scala.io.Source
import scala.util.matching.Regex
import scala.annotation.tailrec
import scala.math
import scala.sys

import au.com.bytecode.opencsv.{CSVWriter, CSVReader}

/** Global constants
  */
private[sqlshell] object Constants {
  val SpecialCommentPrefix = "--sqlshell-"
  val DefaultPrimaryPrompt = "sqlshell> "
  val TokenDelims = """ \t(),"""
}

/** Holds information about a table
  */
class TableSpec(val name: Option[String],
                val schema: Option[String],
                val tableType: Option[String])

/** "Holds" the max history value
  */
class MaxHistorySetting(readline: Readline)
extends Setting with IntValueConverter {

  override def get = readline.history.max

  override def set(newValue: Any) =
    readline.history.max = newValue.asInstanceOf[Int]
}

class MaxCompletionsSetting(readline: Readline)
extends Setting with IntValueConverter {

  override def get = readline.maxShownCompletions
  override def set(newValue: Any) =
    readline.maxShownCompletions = newValue.asInstanceOf[Int]
}

/** Handles enabling/disabling verbose messages.
  */
object LogLevelSetting extends Setting with ValueConverter {
  override val legalValues = logger.Levels.map(_.toString)
  override def get = logger.level.toString
  override def set(newValue: Any) = logger.level = newValue
  override def convertString(newValue: String): Any = newValue
}

/** Handles changes to the "ansi" setting.
  */
object AnsiSetting extends Setting with BooleanValueConverter {

  override def get = logger.useAnsi
  override def set(newValue: Any) =
    logger.useAnsi = newValue.asInstanceOf[Boolean]
}

/** The actual class that implements the command line interpreter.
  *
  * @param config          the loaded configuration file
  * @param dbInfo          the info about the database to which to connect
  * @param readlineLibs    the list of readline libraries to try, in order; or
  *                        `Nil` to use the default set.
  * @param useAnsiColors   `true` to use ANSI terminal colors in various
  *                        output, `false` to avoid them
  * @param showStackTraces whether or not to show stack traces on error
  * @param beVerbose       whether or not to enable verbose messages
  * @param fileToRun       a file to run (then exit), or none for interactive
  *                        mode
  */
class SQLShell(val config: Configuration,
               val dbInfo: DatabaseInfo,
               readlineLibs: List[ReadlineType],
               useAnsiColors: Boolean,
               showStackTraces: Boolean,
               beVerbose: Boolean,
               fileToRun: Option[File])
extends CommandInterpreter("sqlshell", readlineLibs, Constants.TokenDelims)
with Wrapper with Sorter {
  val connector = new DatabaseConnector(config)
  val connectionInfo = connector.connect(dbInfo)
  val connection = connectionInfo.connection
  val historyPath = connectionInfo.configInfo.get("history")

  if (beVerbose)
    logger.level = Verbose

  AnsiSetting.set(useAnsiColors)

  val settings = new Settings(
    ("ansi",           AnsiSetting),
    ("catalog",        new StringSetting("")),
    ("echo",           new BooleanSetting(false)),
    ("logging",        LogLevelSetting),
    ("maxbinary",      new IntSetting(0)),
    ("maxcompletions", new MaxCompletionsSetting(readline)),
    ("maxhistory",     new MaxHistorySetting(readline)),
    ("prompt",         new StringSetting(Constants.DefaultPrimaryPrompt)),
    ("schema",         new StringSetting("")),
    ("showresults",    new BooleanSetting(true)),
    ("showrowcount",   new BooleanSetting(true)),
    ("showtimings",    new BooleanSetting(true)),
    ("sortcolnames",   new BooleanSetting(false)),
    ("stacktrace",     new BooleanSetting(showStackTraces))
  )

  // List of command handlers.

  val aboutHandler = new AboutHandler(this)
  val setHandler = new SetHandler(this)
  val selectHandler = new SelectHandler(this, connection)
  val updateHandler = new UpdateHandler(this, connection)

  /** The handler for unknown commands.
    */
  private val unknownHandler = new UnknownHandler(this,
                                                  selectHandler,
                                                  updateHandler)

  val transactionManager = new TransactionManager(this, connection)
  val handlers = transactionManager.handlers ++
                 List(new HistoryHandler(this),
                      new RedoHandler(this),
                      new RunFileHandler(this),

                      selectHandler,
                      new InsertHandler(this, connection),
                      new DeleteHandler(this, connection),
                      updateHandler,
                      new CreateHandler(this, connection),
                      new AlterHandler(this, connection),
                      new DropHandler(this, connection),
                      new RDBMSSetHandler(this, updateHandler, unknownHandler),

                      new CaptureHandler(this, selectHandler),
                      new ShowHandler(this, connection),
                      new DescribeHandler(this, connection, transactionManager),
                      new CommentHandler(this, connection),
                      new BlockSQLHandler(this, selectHandler, updateHandler),
                      setHandler,
                      new EchoHandler,
                      new ExitHandler,
                      aboutHandler)

  loadSettings(config, connectionInfo)
  val shutdownHook = ShutdownHandler.addShutdownHook {handleAbort}

  aboutHandler.showAbbreviatedInfo
  wrapPrintln("Type \"" + helpHandler.CommandName + "\" for help. Type \"" +
              aboutHandler.CommandName + "\" for more information.")
  logger.verbose("Connected to: " + connectionInfo.jdbcURL)
  println()

  if (fileToRun != None) {
    // Push a reader for the file on the stack. To ensure that we don't
    // fall through to interactive mode, make sure there's an "exit" at
    // the end.

    pushReader(new SourceReader(Source.fromString("exit\n")).readline)
    pushReader(new SourceReader(Source.fromFile(fileToRun.get)).readline)
  }

  /** Cached information about the current database.
    */
  private val databaseInfo = connectionInfo.databaseInfo

  /** The primary prompt string. This method supports substitution.
    */
  override def primaryPrompt = {

     // Renaming the imported template implementation allows it to be
     // changed without affecting the underlying code. We use the
     // Windows-style template engine (with "%var%" syntax), rather than
     // the Unix shell-style one (with "${var}" syntax) to avoid problems
     // in the configuration file, which uses "${var}" substitution.
    import grizzled.string.template.{WindowsCmdStringTemplate => Template}

    /** Resolves a variable reference. See
      * grizzled.string.template.StringTemplate
      */
    def resolveVar(varname: String): Option[String] = {
      varname match {
        case "db"     => connectionInfo.dbName
        case "user"   => databaseInfo.user
        case "dbtype" => databaseInfo.productName
        case "SP"     => Some(" ")
        case _        => None
      }
    }

    // If the prompt is set, map it to a template and resolve it.
    // Otherwise, just use the default prompt.
    settings.stringSetting("prompt").
             map(s => new Template(resolveVar, true).substitute(s)).
             getOrElse(Constants.DefaultPrimaryPrompt)
  }

  /** The second prompt string, used when additional input is being
    * retrieved. Cannot currently be overridden.
    */
  override def secondaryPrompt = "> "

  /** Augment the characters permitted in a multi-character command name,
    * so that they include "-".
    */
  override def StartCommandIdentifier = super.StartCommandIdentifier + "-"

  /** Stuff that happens before the first prompt is ever issued.
    */
  override def preLoop: Unit = {
    historyPath.foreach { path =>

      logger.verbose("Loading history from \"" + path + "\"...")
      history.load(path)
    }
  }

  /** Stuff that happens right before we exit.
    */
  override def postLoop: Unit = {

    if (transactionManager.inTransaction) {
      logger.warning("An uncommitted transaction is open. Rolling it back.")
      transactionManager.rollback()
    }

    historyPath.foreach { path =>
      logger.verbose("Saving history to \"" + path + "\"...")
      history.save(path)
    }
  }

  /** When the user hits interrupt...
    */
  def handleAbort = {
    // Can't always reliably reach inside the history handler (e.g.,
    // readline or editline) without getting a core dump. So, for now,
    // don't.
    logger.error("Aborted.")
  }

  /** What to do on EOF.
    */
  override def handleEOF: CommandAction = {
    logger.verbose("EOF. Exiting.")
    println()

    // Remove the shutdown hook. It's intended to fire on interrupt.
    ShutdownHandler.removeShutdownHook(shutdownHook);
    Stop
  }

  /** The method that's called when an unknown command is typed.
    */
  override def handleUnknownCommand(commandName: String,
                                    unparsedArgs: String): CommandAction = {
    unknownHandler.runCommand(commandName, unparsedArgs)
    KeepGoing
  }

  /** Called when an exception occurs.
    */
  override def handleException(e: Exception): CommandAction = {
    val message = if (e.getMessage == null)
                    e.getClass.getName
                  else
                    e.getMessage

    logger.verbose("Caught exception.")
    logger.error(message)
    if (settings.booleanSettingIsTrue("stacktrace")) {
      e match {
        case es: SQLException =>
          println("SQLState " + es.getSQLState + ", error " + es.getErrorCode)
      }

      e.printStackTrace(System.out)
    }

    KeepGoing
  }

  /** Called before a command is executed. Allows editing of the command.
    *
    * @param line  the command line
    *
    * @return The possibly edited command, Some("") to signal an empty
    *         command, or None to signal EOF.
    */
  override def preCommand(line: String) = {
    if (line.trim().length == 0)
      Some("")

    else {
      if (settings.booleanSettingIsTrue("echo"))
        printf("\n>>> %s\n\n", line)
      Some(line)
    }
  }

  /** Called after a command line is interpreted.
    *
    * @param command      the command that invoked this handler
    * @param unparsedArgs the remainder of the unparsed command line
    *
    * @return KeepGoing to tell the main loop to continue, or Stop to tell
    *         the main loop to be done.
    */
  override def postCommand(command: String,
                           unparsedArgs: String): CommandAction = {
    // Force a newline after each command's output.
    println()
    return KeepGoing
  }

  /** Take a string (which may be null) representing a schema name and
    * return a schema name. If the schema is null, then the default schema
    * is returned. If there is no default schema, then `None` is
    * returned and an error is printed.
    *
    * @param schemaName  the schema name (or null)
    *
    * @return `Some(name)` or `None`
    */
  private[sqlshell] def getSchema(schemaString: String): Option[String] = {
    // Option.apply(null) -> None
    getSchema(Option(schemaString));
  }

  /** Take a string option (which may be `None`) representing a
    * schema name and return a schema name. If the schema is
    * `None`, then the default schema is returned. If there is no
    * default schema, then `None` is returned and an error is
    * printed.
    *
    * @param schemaName  the schema name (or `None`)
    *
    * @return `Some(name)` or `None`
    */
  private[sqlshell] def getSchema(schema: Option[String]): Option[String] = {
    val actualSchema = schema.orElse(settings.stringSetting("schema"))

    if (actualSchema == None)
      wrapPrintln("No schema specified, and no default schema set. " +
                  "To set a default schema, use:\n\n" +
                  ".set schema schemaName\n\n" +
                  "Use a schema name of * for all schemas.")

    actualSchema
  }

  /** Get the table names that match a regular expression filter.
    *
    * @param schema      schema to use, or None for the default
    * @param nameFilter  regular expression to use to filter the names
    *
    * @return a list of matching tables, or Nil
    */
  private[sqlshell] def tables(schema: Option[String],
                               nameFilter: Regex): List[TableSpec] = {
    def getTableData(rs: ResultSet,
                     keep: TableSpec => Boolean): List[TableSpec] = {
      val result = new ListBuffer[TableSpec]

      while (rs.next) {
        val ts = new TableSpec(Option(rs.getString("TABLE_NAME")),
                               Option(rs.getString("TABLE_SCHEM")),
                               Option(rs.getString("TABLE_TYPE")))
        if (keep(ts))
          result += ts
      }

      result.toList
    }

    // Note that it's possible for the retrieval of metadata to fail.
    // Some databases (e.g., MS Access over the JDBC-ODBC bridge) don't
    // support it.

    try {
      val jdbcSchema = getSchema(schema).getOrElse(null)
      val catalog = settings.stringSetting("catalog", null)
      val metadata = connection.getMetaData
      logger.verbose("Getting list of tables. schema=" + jdbcSchema +
                     ", catalog=" + catalog)
      val rs = metadata.getTables(catalog, jdbcSchema, null,
                                  Array("TABLE", "VIEW"))
      try {
        def matches(ts: TableSpec): Boolean =
          (ts != None) &&
        (nameFilter.findFirstIn(ts.name.get) != None)

        getTableData(rs, matches)
      }

      finally {
        rs.close
      }
    }

    catch {
      case e: SQLException =>
        logger.verbose("Failed to retrieve metadata: " + e.getMessage)
      Nil
    }
  }

  /** Get all table data.
    *
    * @param schema      schema to use, or None for the default
    *
    * @return a list of matching tables, or Nil
    */
  private[sqlshell] def tables(schema: Option[String]): List[TableSpec] =
    tables(schema, """.*""".r)

  /** Get all table data.
    *
    * @param schema      schema to use; "" or null for the default
    *
    * @return a list of matching tables, or Nil
    */
  private[sqlshell] def tables(schema: String): List[TableSpec] = {
    val schemaOpt = if ((schema == null) || (schema.trim == "")) None
                    else Some(schema)
    tables(schemaOpt, """.*""".r)
  }

  /** Get table data for a specific named table.
    *
    * @param tableName  the table name, in any mixture of case
    * @param schema the schema to use, or None for the default
    *
    * @return `Some(TableSpec)` or `None` if not found.
    */
  private[sqlshell] def tableSpec(tableName: String, 
                                  schema: Option[String]): Option[TableSpec] = {
    // Convert the name to a case-blind regular expression.

    tables(schema, ("^(?i)" + tableName + "$").r) match {
      case Nil =>
        None
      case ts :: Nil =>
        Some(ts)
      case ts :: rest =>
        logger.error("Multiple tables match \"" + tableName + "\"")
        None
    }
  }

  /** Get the names of all tables in a given schema.
    *
    * @param schema the schema to use, or None for the default
    *
    * @return a sorted list of table names
    */
  private[sqlshell] def tableNames(schema: Option[String]): List[String] = {
    val tableData = tables(schema)
    val tableNames = tableData.filter(_.name != None).map(_.name.get)
    sortByName(tableNames)
  }

  /** Get a list of table names that match a prefix. Useful for completers.
    *
    * @param schema  the schema to use, or None for the default
    * @param prefix  the table name prefix, or None to match all tables
    *
    * @return a sorted list of table names, or Nil for no match
    */
  private[sqlshell] def matchTableNames(schema: Option[String],
                                        prefix: Option[String]):
  List[String] = {

    def handlePrefix(p: String): List[String] = {
      val theTables = tables(schema)
      val lcPrefix = p.toLowerCase

      theTables.filter { ts =>
        (ts.name != None) && (ts.name.get.toLowerCase.startsWith(lcPrefix))
      }
      .map(_.name.get)
    }

    prefix.map(handlePrefix _).getOrElse(tableNames(schema))
  }

  /** Get the names of all schemas in the database.
    *
    * @return a list of schema names
    */
  private[sqlshell] def getSchemas: List[String] = {

    def getResults(rs: ResultSet): List[String] = {
      if (! rs.next)
        Nil
      else
        rs.getString(1) :: getResults(rs)
    }

    val metadata = connection.getMetaData
    val rs = metadata.getSchemas

    try {
      getResults(rs)
    }

    finally {
      rs.close
    }
  }

  private def loadSettings(config: Configuration,
                           connectionInfo: ConnectionInfo) = {

    for ((variable, value) <- config.options("settings"))
      try {
        settings.changeSetting(variable, value)
        logger.verbose("+ %s %s=%s" 
                       format(setHandler.CommandName, variable, value))
      }
    catch {
      case e: UnknownVariableException => warning(e.message)
    }

    connectionInfo.configInfo.get("schema").foreach { schema =>

      settings.changeSetting("schema", schema)
      logger.verbose("+ " + setHandler.CommandName + " schema=" + schema)
    }
  }
}

abstract class SQLShellCommandHandler extends CommandHandler {
  def runCommand(commandName: String, args: String): CommandAction =
    doRunCommand(commandName, removeSemicolon(editArgs(args)))

  def doRunCommand(commandName: String, args: String): CommandAction

  protected def editArgs(args: String): String = args

  protected def removeSemicolon(s: String): String =
    if (s endsWith ";") s.rtrim.substring(0, s.length - 1) else s
}

/** Handles the "exit" command
  */
class ExitHandler extends SQLShellCommandHandler {
  val CommandName = "exit"
  val Help = "Exit SQLShell."

  def doRunCommand(commandName: String, args: String): CommandAction = Stop
}

/** Handles the ".echo" command
  */
class EchoHandler extends SQLShellCommandHandler {
  val CommandName = ".echo"
  val Help = """|Echo the remaining arguments to the terminal. For example:
                |
                |     .echo This will be displayed.""".stripMargin

  def doRunCommand(commandName: String, args: String): CommandAction = {
    println(args)
    KeepGoing
  }
}

/** Handles the ".run" command
  */
class RunFileHandler(val shell: SQLShell) extends SQLShellCommandHandler {
  val CommandName = ".run"
  val Help = """|Load and run the contents of the specified file.
                |
                |     .run path""".stripMargin

  private val completer = new PathnameCompleter

  def doRunCommand(commandName: String, args: String): CommandAction = {
    args.tokenize match {
      case Nil =>
        logger.error("You must specify a file to be run.")

      case file :: Nil =>
        val reader = new SourceReader(Source.fromFile(new File(file)))
        logger.verbose("Loading and running \"" + file + "\"")
        shell.pushReader(reader.readline)

      case _ =>
        logger.error("Too many parameters to " + CommandName + " command.")
    }

    KeepGoing
  }

  override def complete(token: String,
                        allTokens: List[CompletionToken],
                        line: String): List[String] = {
    completer.complete(token, allTokens, line)
  }
}

/** Handles the ".set" command.
  */
class SetHandler(val shell: SQLShell)
extends SQLShellCommandHandler with Sorter {
  val CommandName = ".set"
  val Help = """Change one of the SQLShell settings. Usage:
               |
               |    .set            -- show the current settings
               |    .set var value  -- change a variable
               |    .set var=value  -- change a variable""".stripMargin

  val variables = shell.settings.variableNames
  val varCompleter = new ListCompleter(variables)

  def doRunCommand(commandName: String, args: String): CommandAction = {
    val trimmedArgs = args.trim
    if (trimmedArgs == "")
      showSettings

    else {
      val i = args.indexOf('=')
      val chopAt = if (i >= 0) i else args.indexOf(' ')
      try {
        if (chopAt < 0) {
          val variable = args.trim
          val value = shell.settings(variable)
          printf("%s: %s\n", variable, value.toString)
        }

        else {
          val variable = args.substring(0, chopAt).trim
          val value = args.substring(chopAt + 1).trim
          val strippedValue = stripQuotes(value)
          shell.settings.changeSetting(variable, strippedValue)
        }
      }

      catch {
        case e: UnknownVariableException => logger.warning(e.message)
      }
    }

    KeepGoing
  }

  override def complete(token: String,
                        allTokens: List[CompletionToken],
                        line: String): List[String] = {
    def varValues(varName: String): List[String] = {
      try {
        shell.settings.legalValuesFor(varName)
      }
      catch {
        case e: UnknownVariableException => Nil
      }
    }

    allTokens match {
      case Nil =>
        assert(false) // shouldn't happen
        Nil

      case LineToken(cmd) :: Cursor :: rest =>
        Nil

      case LineToken(cmd) :: Delim :: Cursor :: rest =>
        varCompleter.complete(token, allTokens, line)

      case LineToken(cmd) :: Delim :: LineToken(name) :: Cursor :: rest =>
        varCompleter.complete(token, allTokens, line)

      case LineToken(cmd) :: Delim :: LineToken(name) :: Delim :: Cursor :: x =>
        varValues(name)

      case LineToken(cmd) :: Delim :: LineToken(name) :: Delim ::
          LineToken(partialVal) :: Cursor :: rest =>
            varValues(name).filter(_ startsWith partialVal.toLowerCase)

      case _ =>
        Nil
    }
  }

  private def stripQuotes(s: String): String = {
    val ch = s(0)
    if ("\"'" contains ch) {
      if (s.length == 1)
        throw new SQLShellException("Unbalanced quotes in value: " + s)

      if (s.last != ch)
        throw new SQLShellException("Unbalanced quotes in value: " + s)

      s.substring(1, s.length - 1)
    }

      else
        s
  }

  private def showSettings = {
    val varWidth = max(variables.map(_.length): _*)
    val fmt = "%" + varWidth + "s: %s"

    for {variable <- variables
         val value = shell.settings(variable).toString}
      println(fmt format(variable, value))
  }
}

/** Handles the ".about" command.
  */
class AboutHandler(val shell: SQLShell)
extends SQLShellCommandHandler with Sorter with Wrapper {
  import java.util.Properties

  val CommandName = ".about"
  val Help = "Display information about SQLShell"

  val aboutInfo = new AboutInfo

  private def getReadline(ignored: String = ""): Option[String] =
    Some(shell.readline.toString)

  private val Keys = List[(String, String => Option[String], String)] (
    ("Build date", aboutInfo.apply, "build.date"),
    ("Built with", aboutInfo.apply, "build.compiler"),
    ("Build OS",   aboutInfo.apply, "build.os"),
    ("Running on", aboutInfo.apply, "java.vm"),
    ("Readline",   getReadline,     "")
  )

  def doRunCommand(commandName: String, args: String): CommandAction = {
    showFullInfo
    KeepGoing
  }

  def showAbbreviatedInfo = {
    println(aboutInfo.identString)
    println(aboutInfo.copyright)
    println("Using " + getReadline().get)
  }

  def showFullInfo = {
    println(aboutInfo.identString)
    println

    // Allow for trailing ":" and space.
    val maxLabelLength = 2 + Keys.foldLeft(0) {
      (sum, entry) => math.max(sum, entry._1.length)
    }

    for ((label, func, key) <- Keys) {
      func(key).foreach {
        value =>

          wrapPrintln((label + ":").padTo(maxLabelLength, ' '), value)
      }
    }
  }
}

/** JDBC helper routines.
  */
trait JDBCHelper {
  /** With a given JDBC connection, open a new statement (via
    * `Connection.createStatement()`), and execute a block of
    * code with the statement, ensuring that the statement is closed when
    * the block completes.
    *
    * @param connection  the JDBC connection
    * @param code        the block to execute with the created statement
    */
  protected def withSQLStatement(connection: Connection)
  (code: Statement => Unit) = {
    val statement = connection.createStatement
    withCloseable(statement)(code)
  }

  /** Run a block of code on a `ResultSet`, ensuring that the
    * `ResultSet` is closed. Use this method, instead of
    * `grizzled.util.withCloseable()`, until a Scala reflection bug
    * is fixed. Using `withCloseable()` directly with a ResultSet
    * fails with a reflection error, for JDBC drivers that override the
    * public `ResultSet.close()` method with one that's different
    * (e.g., has a `synchronized` modifier, or a `protected`
    * modifier). See Scala bug #2136:
    * http://lampsvn.epfl.ch/trac/scala/ticket/2318
    *
    * @param rs    the `ResultSet`
    * @param code  the code to run with the result set
    */
  protected def withResultSet(rs: ResultSet)(code: ResultSet => Unit) = {
    try {
      code(rs)
    }

    finally {
      rs.close
    }
  }

  /** Get a value from a result set, returning it as an option.
    *
    * @param columnKey column key (name, whatever)
    * @param get       function to retrieve value (e.g., `rs.getInt`)
    *
    * @return Some(value) or None
    */
  protected def rsGetOpt[T](columnKey: String, get: String => T): Option[T] = {
    // Option.apply(null) -> None
    Option(get(columnKey))
  }
}

abstract class SQLHandler(val shell: SQLShell, val connection: Connection)
extends SQLShellCommandHandler with Timer with JDBCHelper with CompleterHelper {

  override def moreInputNeeded(lineSoFar: String): Boolean = {
    (! lineSoFar.ltrim.endsWith(";"))
  }

  override def complete(token: String,
                        allTokens: List[CompletionToken],
                        line: String): List[String] = {
    allTokens match {
      case Nil =>
        assert(false) // shouldn't happen
        Nil

      case LineToken(cmd) :: Cursor :: rest =>
        Nil

      case LineToken(cmd) :: Delim :: rest =>
        tokenBeforeCursor(rest) match {
          case (None | Some(Delim)) =>
            // All table names
            shell.tableNames(None)
          case Some(LineToken(prefix)) =>
            // Table names matching the prefix
            shell.matchTableNames(None, Some(prefix))
          case _ =>
            shell.tableNames(None)
        }

      case _ =>
        shell.tableNames(None)
    }
  }

  override protected def editArgs(args: String): String =
    args.replaceAll("[\n\r]", " ")
}

/** The interface for a class that can consume a result set.
  */
trait ResultSetHandler {
  def startResultSet(metadata: ResultSetMetaData, statement: String): Unit =
    return
  def handleRow(rs: ResultSet): Unit
  def endResultSet: Unit = return
  def closeResultSetHandler: Unit = return
}

abstract class ResultSetStringifier(maxBinary: Int) {
  private val DateFormatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.S")

  /** Map a result set row, returning the each column and its name.
    *
    * @param rs       the result set
    * @param metadata the result set's metadata
    * @param rowMap   preallocated array buffer into which to dump information.
    *                 Data is indexed by (columnNumber - 1)
    *
    * @return the row map buffer, as an array
    */
  def mapRow(rs: ResultSet,
             metadata: ResultSetMetaData,
             rowMap: ArrayBuffer[String]): Array[String] = {
    import grizzled.io.RichReader._       // for the readSome() method
    import grizzled.io.RichInputStream._  // for the readSome() method
    import grizzled.io.util.useThenClose

    val NULL_STR = "NULL"

    def getDateString(i: Int, dateFromResultSet: (Int) => Date): String = {
      val date = dateFromResultSet(i)
      if (rs.wasNull)
        NULL_STR
      else
        DateFormatter.format(date)
    }

    def clobString(i: Int): String = {
      if (maxBinary == 0)
        "<clob>"

      else {
        val r = rs.getCharacterStream(i)
        if (rs.wasNull)
          NULL_STR

        else {
          useThenClose(r) {
            // Read one more than the binary length. If we get
            // that many, then display an ellipsis.

            val buf = r.readSome(maxBinary + 1)
            buf.length match {
              case 0 =>                    ""
              case n if (n > maxBinary) => buf.mkString("") + "..."
              case n =>                    buf.mkString("")
            }
          }
        }
      }
    }

    def binaryString(i: Int): String = {
      if (maxBinary == 0)
        "<blob>"

      else {
        val is = rs.getBinaryStream(i)
        if (rs.wasNull)
          NULL_STR

        else {
          useThenClose(is) {
            // Read one more than the binary length. If we get
            // that many, then display an ellipsis.

            val buf = is.readSome(maxBinary + 1)
            val ellipsis = buf.length > maxBinary
            val conv = (b: Byte) =>
              b.asInstanceOf[Int].toHexString.toList match {
                case digit :: Nil => "0" + digit
                case digits       => digits mkString ""
              }

            val hexString = buf.map(conv(_)) mkString ""
            if (ellipsis) hexString + "..." else hexString
          }
        }
      }
    }

    def objToString(i: Int): String = {
      val o = rs.getObject(i)
      if (rs.wasNull)
        NULL_STR
      else
        o.toString
    }

    def colAsString(i: Int): String = {
      // What we'd like to do:
      /*
       rs.getObject(i)
       if (rs.wasNull) "NULL" else nonNullColAsString(i)
        */

      // Problem: With some RDBMS types (e.g., Microsoft Access, with the
      // JDBC-ODBC bridge driver), double retrieval of the column causes
      // an error. To solve this problem, we have to ensure that we
      // retrieve the column value once, which means the null test
      // has to go in multiple places.

      metadata.getColumnType(i) match {
        // Handle just the oddballs. getObject(i).toString should be
        // sufficient to handle the other column types.

        case SQLTypes.BINARY        => binaryString(i)
        case SQLTypes.BLOB          => binaryString(i)
        case SQLTypes.CLOB          => clobString(i)
        case SQLTypes.DATE          => getDateString(i, rs.getDate)
        case SQLTypes.LONGVARBINARY => binaryString(i)
        case SQLTypes.LONGVARCHAR   => clobString(i)
        case SQLTypes.NULL          => "<null>"
        case SQLTypes.TIME          => getDateString(i, rs.getTime)
        case SQLTypes.TIMESTAMP     => getDateString(i, rs.getTimestamp)
        case SQLTypes.VARBINARY     => binaryString(i)
        case _                      => objToString(i)
      }
    }

    // Actual function

    for {i <- 1 to metadata.getColumnCount
         colName = metadata.getColumnName(i)}
    rowMap += colAsString(i)

    rowMap.toArray
  }
}

private[sqlshell] case class ColumnData(tableName: String,
                                        columnName: String,
                                        rsIndex: Int,
                                        displayWidth: Int) {
  val format = "%-" + displayWidth + "s"
}

/** Encapsulates the preprocessed results from a query.
  */
private[sqlshell] class PreprocessedResults(val metadata: ResultSetMetaData,
                                            val dataFile: File)
extends Iterable[Array[String]] {

  import java.io.{EOFException,
                  FileInputStream,
                  FileOutputStream,
                  InputStream,
                  InputStreamReader,
                  OutputStreamWriter,
                  Reader}

  private val columnData = new ListBuffer[ColumnData]
  private var totalRows = 0

  // Where we serialize the data. NOTE: SQLShell used to use an
  // ObjectOutputStream, but ran into memory problems doing so.
  // Since it's all strings anyway, CSV is fine.
  private val out = new CSVWriter(
    new OutputStreamWriter(new FileOutputStream(dataFile), "UTF-8")
  )

  // Get the column names and initialize the associated size.

  for {i <- 1 to metadata.getColumnCount
       columnName = metadata.getColumnName(i)
       tableName = metadata.getTableName(i)}
    columnData += ColumnData(tableName, columnName, i, columnName.length)

  class ResultIterator extends Iterator[Array[String]] {
    val in = new CSVReader(
      new InputStreamReader(new FileInputStream(dataFile), "UTF-8")
    )
    val buf = new ArrayBuffer[String]

    // Is there something there?
    def hasNext: Boolean = {
      try {
        buf.clear
        val row = deserializeRow(in)
        if (row == null) {
          buf.clear
          in.close
          false
        }
        else {
          buf ++= row
          true
        }
      }

      catch {
        case _: EOFException => {
          in.close
          buf.clear
          false
        }
      }
    }

    // Get the next something.
    def next: Array[String] = {
      assert(buf.length > 0)
      buf.toArray
    }
  }

  override def elements: Iterator[Array[String]] = iterator
  override def iterator = new ResultIterator

  def rowCount = totalRows

  /** Return the stored column names, in the order they appeared in
    * the result set. The names are qualified by the table names.
    */
  def columnNamesAndSizes = columnData.toList

  /** Save a mapped row to the specified object stream.
    *
    * @param out  the `ObjectOutputStream` to which to save the row
    * @param row  the data, converted to a string
    */
  def saveMappedRow(row: Array[String]) {
    serializeRow(row)
    totalRows += 1

    for {i <- 1 to metadata.getColumnCount
         name = metadata.getColumnName(i)} {
      val value = row(i - 1)
      val size = value.length
      val data = columnData(i - 1)
      if (size > data.displayWidth)
        columnData(i - 1) = data copy (displayWidth = size)
    }
  }

  def flush = out.flush

  private def serializeRow(row: Array[String]) = out.writeNext(row)
  private def deserializeRow(in: CSVReader): Array[String] = in.readNext
}

private[sqlshell] trait SelectResultSetHandler extends ResultSetHandler {
  var totalRows = 0
  override def handleRow(rs: ResultSet) = totalRows += 1
}

/** Used by the select handler to process and cache a result set.
  */
private[sqlshell] class ResultSetCacheHandler(tempFile: File,
                                              val maxBinary: Int)
extends ResultSetStringifier(maxBinary) with SelectResultSetHandler {

  import java.io.{FileOutputStream, ObjectOutputStream}

  var results: PreprocessedResults = null
  val rowMap = new ArrayBuffer[String]

  override def startResultSet(metadata: ResultSetMetaData,
                              statement: String): Unit = {
    results = new PreprocessedResults(metadata, tempFile)
  }

  override def handleRow(rs: ResultSet) {
    super.handleRow(rs)
    rowMap.clear
    results.saveMappedRow(mapRow(rs, results.metadata, rowMap))
  }

  override def endResultSet = results.flush
}

private [sqlshell] class ResultSetNoCacheHandler extends SelectResultSetHandler

/** Handles SQL comments.
  */
class CommentHandler(shell: SQLShell, connection: Connection)
extends SQLShellCommandHandler with HiddenCommandHandler {
  val CommandName = "--"

  override def matches(candidate: String): Boolean = {
    candidate.startsWith("--") &&
    (! candidate.startsWith(Constants.SpecialCommentPrefix))
  }

  def doRunCommand(command: String, args: String): CommandAction = KeepGoing
}

/** Handles a block SQL command, consisting of a multi-line hunk of SQL
  * between two delimiting lines.
  */
class BlockSQLHandler(shell: SQLShell,
                      val selectHandler: SelectHandler,
                      val updateHandler: UpdateHandler)
extends SQLShellCommandHandler
with BlockCommandHandler
with HiddenCommandHandler {

  import scala.collection.mutable.ListBuffer

  val CommandName = Constants.SpecialCommentPrefix + "block-begin"
  val EndCommand = ("""^\s*""" + Constants.SpecialCommentPrefix +
                    """block-end\s*$""").r

  override def doRunCommand(command: String, args: String): CommandAction = {
    assert(command == CommandName)

    // The last argument will match EndCommand. Kill it.

    val argArray = args.split("\n+")
    assert (argArray.length > 0)
    assert (EndCommand.findFirstIn(argArray.last) != None)
    val newArgs = argArray.slice(0, argArray.length - 1)
    val block = newArgs mkString "\n"
    println(block)

    // Run as an update only.

    updateHandler.runCommand("", block)
  }
}

/** Handles SQL "SELECT" statements.
  */
class SelectHandler(shell: SQLShell, connection: Connection)
extends SQLHandler(shell, connection) with Timer {

  import java.io.{File, FileOutputStream, ObjectOutputStream}

  val tempFile = File.createTempFile("sqlshell", ".dat")
  tempFile.deleteOnExit

  val CommandName = "select"
  val Help = """Issue a SQL SELECT statement and display the results"""
  val ColumnSeparator = "  "

  private val resultHandlers = MutableMap.empty[String, ResultSetHandler]

  /** Run the "SELECT" command and dump the output.
    *
    * @param commandName the command (i.e., "select")
    * @param args        the remainder of the SELECT command
    *
    * @return KeepGoing, always
    */
  def doRunCommand(commandName: String, args: String): CommandAction = {
    val fullStatement = commandName + " " + removeSemicolon(args)
    withSQLStatement(connection) { statement =>

      val (elapsed, rs) = time[ResultSet] {
        statement.executeQuery(fullStatement)
      }

      withResultSet(rs) { rs =>
        dumpResults(elapsed, rs, fullStatement)
      }
    }

    KeepGoing
  }

  /** Add a result handler to the list of result handlers.
    *
    * @param key     the key for the handler
    * @param handler the `ResultSetHandler` to add
    */
  def addResultHandler(key: String, handler: ResultSetHandler) = {
    assert(! (resultHandlers contains key))
    resultHandlers += (key -> handler)
  }

  /** Get a result handler from the list of result handlers.
    *
    * @param key     the key for the handler
    *
    * @return `Some(handler)` or `None`
    */
  def getResultHandler(key: String): Option[ResultSetHandler] =
    resultHandlers.get(key)

  /** Remove a result handler from the list of result handlers
    *
    * @param key     the key for the handler
    *
    * @return `Some(handler)` or `None`
    */
  def removeResultHandler(key: String): Option[ResultSetHandler] = {
    val handler = getResultHandler(key)
    handler.foreach {r => resultHandlers -= key}
    handler
  }

  /** Dump the results of a query.
    *
    * @param queryTime  the number of milliseconds the query took to run;
    *                   displayed with the results
    * @param rs         the JDBC result set
    * @param statement  the statement that was issued, as a string
    */
  protected def dumpResults(queryTime: Long,
                            rs: ResultSet,
                            statement: String): Unit = {
    logger.verbose("Processing results...")

    val metadata = rs.getMetaData
    val maxBinary = shell.settings.intSetting("maxbinary")
    val cacheHandler = new ResultSetCacheHandler(tempFile, maxBinary)
    val noCacheHandler = new ResultSetNoCacheHandler
    val resultHandler =
      if (shell.settings.booleanSettingIsTrue("showresults"))
        cacheHandler
      else
        noCacheHandler

    val handlers = resultHandler :: resultHandlers.valuesIterator.toList

    if (shell.settings.booleanSettingIsTrue("showtimings"))
      println("Execution time: " + formatInterval(queryTime))

    val retrievalTime = time {
      try {
        for (h <- handlers)
          h.startResultSet(metadata, statement)

        while (rs.next) {
          for (h <- handlers)
            h.handleRow(rs)
        }
      }

      finally {
        rs.close
        handlers.foreach(_.endResultSet)
      }
    }

    // Now, post-process (i.e., display) the results cached by the
    // cache handler

    if (shell.settings.booleanSettingIsTrue("showtimings"))
      println("Retrieval time: " + formatInterval(retrievalTime))

    if (shell.settings.booleanSettingIsTrue("showrowcount")) {
      if (resultHandler.totalRows == 0)
        println("No rows returned.")
      else if (resultHandler.totalRows == 1)
        println("1 row returned.")
      else
        printf("%d rows returned.\n", resultHandler.totalRows)
    }

    if (shell.settings.booleanSettingIsTrue("showresults")) {
      val preprocessedResults = cacheHandler.results

      // Note: Scala's format method doesn't left-justify.
      def fmt = new java.util.Formatter

      // Print column names...
      val colData = preprocessedResults.columnNamesAndSizes

      println()
      println(colData.map(c => fmt.format(c.format, c.columnName)).
              mkString(ColumnSeparator))

      // ...and a separator line.

      println(colData.map(c => fmt.format(c.format, "-" * c.displayWidth)).
              mkString(ColumnSeparator))

      // Now, load the serialized results and dump them.

      for (resultRow <- preprocessedResults) {
        // colData(n).rsIndex is the result set index, which is 1-based.
        // resultRow is 0-based.
        println(colData.map(c => fmt.format(c.format,
                                            resultRow(c.rsIndex - 1))).
                mkString(ColumnSeparator))
      }
    }
  }
}

/** Handles any command that calls Statement.executeUpdate(). This class
  * is abstract so that specific handlers can be instantiated for individual
  * commands (allowing individual help).
  */
abstract class AnyUpdateHandler(shell: SQLShell, connection: Connection)
extends SQLHandler(shell, connection) with Timer {

  val mustRemoveSemiColon = true

  def doRunCommand(commandName: String, args: String): CommandAction = {
    val newArgs = if (mustRemoveSemiColon)
                    removeSemicolon(args)
                  else
                    args

    withSQLStatement(connection) { statement =>

      val (elapsed, rows) = time[Int] {
        statement.executeUpdate(commandName + " " + newArgs)
      }

      if (shell.settings.booleanSettingIsTrue("showrowcount")) {
        if (rows == 0)
          println("No rows affected.")
        else if (rows == 1)
          println("1 row affected.")
        else
          printf("%d rows affected.\n", rows)
      }

      if (shell.settings.booleanSettingIsTrue("showtimings"))
        println("Execution time: " + formatInterval(elapsed))
    }

    KeepGoing
  }
}

/** Handles the .capture command
  */
class CaptureHandler(shell: SQLShell, selectHandler: SelectHandler)
extends SQLShellCommandHandler {
  val CommandName = ".capture"
  val Help =
"""|Captures the results of queries to a CSV file.
   |
   |To turn capture on:
   |
   |    .capture to /path/to/file  -- captures to specified file
   |    .capture on                -- captures to a temporary file
   |
   |To turn capture off:
   |
   |    .capture off
   |
   |Example:
   |
   |    .capture to /tmp/results.csv
   |    SELECT * from foo;
   |    SELECT * from bar;
   |    .capture off
   |
   |SQLShell opens the file for writing (truncating it, if it already exists).
   |Then, SQLShell writes each result set to the file, along with column
   |headers, until it sees ".capture off".""".stripMargin

  private val HandlerKey = this.getClass.getName
  private val maxBinary = shell.settings.intSetting("maxbinary")

  private class SaveToCSVHandler(path: File)
  extends ResultSetStringifier(maxBinary) with ResultSetHandler {

    import java.io.{FileOutputStream, OutputStreamWriter}

    val writer = new CSVWriter(
      new OutputStreamWriter(new FileOutputStream(path), "UTF-8")
    )
    private var metadata: ResultSetMetaData = null
    private val rowMap = new ArrayBuffer[String]

    override def startResultSet(metadata: ResultSetMetaData,
                                statement: String): Unit = {
      this.metadata = metadata

      val headers = {for {i <- 1 to metadata.getColumnCount
                          name = metadata.getColumnName(i)}
                       yield name}.toArray
      writer.writeNext(headers)
    }

    def handleRow(rs: ResultSet): Unit = {
      rowMap.clear
      mapRow(rs, metadata, rowMap)
      writer.writeNext(rowMap.toArray)
      rowMap.clear
    }

    override def endResultSet: Unit = {
      this.metadata = null
      writer.flush
    }

    override def closeResultSetHandler: Unit = writer.close
  }

  def doRunCommand(commandName: String, args: String): CommandAction = {
    args.tokenize match {
      case Nil =>
        usage

      case "on" :: Nil =>
        installHandler(File.createTempFile("sqlshell", ".csv"))

      case "to" :: Nil =>
        logger.error("Missing path to which to save query data.")

      case "to" :: path :: Nil =>
        installHandler(new File(path))

      case "off" :: Nil =>
        removeHandler

      case _ =>
        usage
    }

    KeepGoing
  }

  private def installHandler(path: File) = {
    selectHandler.getResultHandler(HandlerKey) match {
      case None =>
        selectHandler.addResultHandler(HandlerKey,
                                       new SaveToCSVHandler(path))
      println("Capturing result sets to: " + path.getPath)

      case handler =>
        logger.error("You're already capturing query results.")
    }
  }

  private def removeHandler = {
    selectHandler.removeResultHandler(HandlerKey) match {
      case None =>
        logger.error("You're not currently capturing query results.")

      case Some(handler) =>
        handler.closeResultSetHandler
      println("No longer capturing query results.")
    }
  }

  private def usage =
    logger.error("Usage: .capture to /path/to/file\n" +
                 "   or: .capture off")

}

/** Handles a SQL "UPDATE" command.
  */
class UpdateHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "update"
  val Help = """Issue a SQL UPDATE statement."""
}

/** Handles a SQL "INSERT" command.
  */
class InsertHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "insert"
  val Help = """Issue a SQL INSERT statement."""
}

/** Handles a SQL "DELETE" command.
  */
class DeleteHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "delete"
  val Help = """Issue a SQL DELETE statement."""
}

/** Handles a SQL "ALTER" command.
  */
class AlterHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "alter"
  val Help = """Issue a SQL ALTER statement."""
}

/** Handles a SQL "CREATE" command.
  */
class CreateHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "create"
  val Help = """Issue a SQL CREATE statement."""
}

/** Handles a SQL "DROP" command.
  */
class DropHandler(shell: SQLShell, connection: Connection)
extends AnyUpdateHandler(shell, connection) {
  val CommandName = "drop"
  val Help = """Issue a SQL DROP statement."""
}

/** Contains handlers and state for transaction management.
  */
class TransactionManager(val shell: SQLShell, val connection: Connection) {
  /** Determines whether a transaction is open or not. If a transaction
    * is open, autocommit will be off. Otherwise, it will be on.
    *
    * @return `true` if a transaction is open (i.e., "BEGIN"
    *         was invoked, without yet seeing `COMMIT` or
    *         `ROLLBACK`; `false` if not.
    */
  def inTransaction: Boolean = {
    try {
      ! connection.getAutoCommit
    }

    catch {
      case e: SQLException =>
        logger.error("Cannot determine autocommit status: " +
                     e.getMessage)
      false
    }
  }

  /** Commit a transaction.
    */
  def commit(): Unit = {
    if (! inTransaction)
      logger.warning("Not in a transaction. Commit ignored.")

    else {
      connection.commit()
      setAutoCommit(true)
    }
  }

  /** Roll a transaction back.
    */
  def rollback(): Unit = {
    if (! inTransaction)
      logger.warning("Not in a transaction. Rollback ignored.")

    else {
      connection.rollback()
      setAutoCommit(true)
    }
  }

  private def setAutoCommit(onOff: Boolean) = {
    try {
      connection.setAutoCommit(onOff)
    }

    catch {
      case e: SQLException =>
        logger.error("Cannot change autocommit status: " + e.getMessage)
    }
  }

  trait NoArgChecker {
    protected def checkForNoArgs(commandName: String, args: String) = {
      if (args.trim != "")
        logger.warning("Ignoring arguments to " + commandName +
                       " command.")
    }
  }

  /** Handles a SQL "BEGIN" pseudo-command.
    */
  object BeginHandler extends SQLShellCommandHandler with NoArgChecker {
    val CommandName = "begin"
    val Help =
"""|Start a new transaction. BEGIN switches disables autocommit for the
   |database connection. Any SQL updates to the database occur within a
   |transaction that must either be committed or rolled back. (See the "commit"
   |and "rollback" commands.)""".stripMargin

    def doRunCommand(commandName: String, args: String): CommandAction = {
      checkForNoArgs(commandName, args)
      if (inTransaction)
        logger.warning("Already in a transaction. BEGIN ignored.")
      else
        setAutoCommit(false)

      KeepGoing
    }
  }

  /** Handles a SQL "COMMIT" command.
    */
  object CommitHandler extends SQLShellCommandHandler with NoArgChecker {
    val CommandName = "commit"
    val Help =
"""|Commits a transaction. This command is only within a transaction (that is,
   |if "begin" has been issued, but neither "commit" nor "rollback" has yet
   |issued. (See the "begin" and "rollback" commands.)""".stripMargin

    def doRunCommand(commandName: String, args: String): CommandAction = {
      checkForNoArgs(commandName, args)
      commit()
      KeepGoing
    }
  }

  /** Handles a SQL "ROLLBACK" command.
    */
  object RollbackHandler extends SQLShellCommandHandler with NoArgChecker {
    val CommandName = "rollback"
    val Help =
"""|Rolls a transaction back. This command is only within a transaction (that
   |is, if "begin" has been issued, but neither "commit" nor "rollback" has yet
   |issued. (See the "begin" and "commit" commands.)""".stripMargin

    def doRunCommand(commandName: String, args: String): CommandAction = {
      checkForNoArgs(commandName, args)
      rollback()
      KeepGoing
    }
  }

  /** Gets all the handlers supplied by this class.
    */
  val handlers = List(BeginHandler, CommitHandler, RollbackHandler)
}

/** Handles a "set" command, mostly as a special case for SQL Server.
  */
class RDBMSSetHandler(shell: SQLShell,
                      val updateHandler: UpdateHandler,
                      val unknownHandler: UnknownHandler)
extends SQLShellCommandHandler {

  val CommandName = "set"
  val Help = """Handle a "set" command."""
  val dbVendor = shell.connectionInfo.databaseInfo.productName

  def doRunCommand(commandName: String, args: String): CommandAction = {
    dbVendor match {
      case Some(s) if s.toLowerCase.contains("sql server") =>
        sqlServerDoSet(commandName, args)
        KeepGoing

      case _ =>
        unknownHandler.runCommand(commandName, args)
    }
  }

  private def sqlServerDoSet(commandName: String, args: String): Unit = {
    // SQL Server. Run the "set" as an update. It'll throw an
    // exception, but it'll throw one for a query, too.

    try {
      updateHandler.runCommand(commandName, args)
    }

    catch {
      case e: SQLException =>
        // Kludge: Ignore exception if it's bitching about a result set.
        // Could use the SQL State, but it's not all granular enough
        // (see as 07000 experimentally, which isn't helpful).
        val msg = e.getMessage.toLowerCase
        if (msg.indexOf("must not return a result") == -1)
          throw e
    }
  }
}

/** Handles any unknown command.
  */
class UnknownHandler(shell: SQLShell,
                     val selectHandler: SelectHandler,
                     val updateHandler: UpdateHandler)
extends SQLShellCommandHandler {

  val CommandName = ""
  val Help = """Issue an unknown SQL statement."""

  def doRunCommand(commandName: String, args: String): CommandAction = {
    if (commandName startsWith Constants.SpecialCommentPrefix) {
      sys.error("Unknown special command.");
    }

    else {
      // Try it as both a query and an update.

      try {
        selectHandler.runCommand(commandName, args)
      }

      catch {
        case _ => updateHandler.runCommand(commandName, args)
      }
    }

    KeepGoing
  }
}

class DescribeHandler(val shell: SQLShell,
                      val connection: Connection,
                      val transactionManager: TransactionManager)
extends SQLShellCommandHandler with Wrapper with JDBCHelper with Sorter {
  val CommandName = ".desc"
  val Help =
"""|Describe database objects.
   |
   |.desc database
   |    Show information about the database, including the database vendor,
   |    the JDBC driver, and whether or not a transaction is currently open.
   |
   |.desc table [full]
   |
   |    Describe a table, showing the column names and their types. If "full"
   |    is specified, show the indexes and constraints, as well.
   |
   |    In the first form of the command, the schema name identifies the schema
   |    in which to find the table. In the second form of the command, the
   |    schema is taken from the default schema.
   |
   |    See ".set schema" for more information.""".stripMargin

  private case class ColumnInfo(val columnName: String,
                                val typeString: String,
                                val ordinalPosition: Int,
                                val schema: Option[String],
                                val catalog: Option[String])

  private val jdbcTypeNames = getJdbcTypeNames

  private val subCommands = List("database")
  private def subCommandCompleter = {
    val tables = shell.tableNames(None)
    new ListCompleter(subCommands ++ tables, _.toLowerCase)
  }

  def doRunCommand(commandName: String, args: String): CommandAction = {
    args.tokenize match {
      case "database" :: stuff :: Nil =>
        describeDatabase
      case "database" :: Nil =>
        describeDatabase
      case table :: "full" :: Nil =>
        describeTable(table, true)
      case table :: Nil =>
        describeTable(table, false)
      case Nil =>
        logger.error("Missing the object to describe.")
      case _ =>
        logger.error("Bad .desc command: \"" + args + "\"")
    }

    KeepGoing
  }

  override def complete(token: String,
                        allTokens: List[CompletionToken],
                        line: String): List[String] = {
    allTokens match {
      case Nil =>
        assert(false) // shouldn't happen
        Nil

      case LineToken(cmd) :: Delim :: Cursor :: Nil =>
        // Command filled in (obviously, or we wouldn't be in here),
        // but first argument not.
        subCommandCompleter.complete(token, allTokens, line)

      case LineToken(cmd) :: Delim :: LineToken("database") :: Cursor :: Nil =>
        Nil

      case LineToken(cmd) :: Delim :: LineToken(table) :: Delim :: Cursor ::
           Nil =>
        List("full")

      case LineToken(cmd) :: Delim :: LineToken(table) :: Delim ::
           LineToken(arg) :: Cursor :: Nil =>
        if ("full".startsWith(arg))
          List("full")
        else
          Nil

      case LineToken(cmd) :: Delim :: LineToken(arg) :: Cursor :: Nil =>
        subCommandCompleter.complete(token, allTokens, line)

      case _ =>
        Nil
    }
  }

  private def describeDatabase = {
    val info = shell.connectionInfo.databaseInfo

    val inTransactionStr = if (transactionManager.inTransaction) "yes"
                           else "no"

    def toString(opt: Option[String]): String = opt.getOrElse("?")

    wrapPrintln("Database name:         ",
                toString(shell.connectionInfo.dbName))
    wrapPrintln("Connected to database: ", toString(info.jdbcURL))
    if (info.user != None)
      wrapPrintln("Connected as user:     ", info.user.get)
    wrapPrintln("Database vendor:       ", toString(info.productName))
    wrapPrintln("Database version:      ", toString(info.productVersion))
    wrapPrintln("JDBC driver:           ", toString(info.driverName))
    wrapPrintln("JDBC driver version:   ", toString(info.driverVersion))
    wrapPrintln("Transaction isolation: ", info.isolation)
    wrapPrintln("Open transaction?      ", inTransactionStr)
  }

  private def numSize(columnSize: Int, decimalDigits: Option[Int]) = {
    // Column size is precision, decimal digits defines scale.
    val s = (columnSize, decimalDigits) match {
      case (p, None)     => p.toString
      case (p, Some(sc)) => p.toString + "," + sc.toString
    }

    "(" + s + ")"
  }

  private def charSize(size: Int) =
    if (size > 0) "(" + size.toString + ")" else ""

  /** Get info about a column. The result set is assumed to be positioned
    * at a row returned by DatabaseMetaData.getColumns.
    *
    * @param rs  the result set
    *
    * @return ColumnInfo
    */
  private def columnInfo(rs: ResultSet): ColumnInfo = {
    val columnName = rs.getString("COLUMN_NAME")
    val columnSize = rs.getInt("COLUMN_SIZE")
    val decimalDigits = rsGetOpt("DECIMAL_DIGITS", rs.getInt)
    val jdbcType = rs.getInt("DATA_TYPE")
    val ordinalPosition = rs.getInt("ORDINAL_POSITION")
    val schema = util.noneIfEmpty(rs.getString("TABLE_SCHEM"))
    val catalog = util.noneIfEmpty(rs.getString("TABLE_CAT"))

    // This weirdness handles SQLite, among other things.
    val typeName = rsGetOpt("TYPE_NAME", rs.getString) match {
      case None if (jdbcType == SQLTypes.NULL) =>
        "?unknown?"
      case None =>
        jdbcTypeNames.getOrElse(jdbcType, "?unknown?")
      case Some(t) =>
        t
    }

    val typeQualifier = jdbcType match {
      case SQLTypes.CHAR          => charSize(columnSize)
      case SQLTypes.CLOB          => charSize(columnSize)
      case SQLTypes.DECIMAL       => numSize(columnSize, decimalDigits)
      case SQLTypes.DOUBLE        => numSize(columnSize, decimalDigits)
      case SQLTypes.FLOAT         => numSize(columnSize, decimalDigits)
      case SQLTypes.LONGVARCHAR   => charSize(columnSize)
      case SQLTypes.NUMERIC       => numSize(columnSize, decimalDigits)
      case SQLTypes.REAL          => numSize(columnSize, decimalDigits)
      case SQLTypes.VARCHAR       => charSize(columnSize)
      case _                      => ""
    }

    val fullTypeName = typeName + typeQualifier
    val nullable = rs.getInt("NULLABLE") match {
      case ResultSetMetaData.columnNoNulls         => "NOT NULL"
      case ResultSetMetaData.columnNullable        => "NULL"
      case ResultSetMetaData.columnNullableUnknown => "NULL?"
    }

    ColumnInfo(columnName,
               fullTypeName + " " + nullable,
               ordinalPosition,
               schema,
               catalog)
  }

  private def describeTable(table: String, full: Boolean) = {
    def columnDescriptions(rs: ResultSet): List[ColumnInfo] = {
      def nextDesc: List[ColumnInfo] = {
        if (rs.next)
          columnInfo(rs) :: nextDesc
        else
          Nil
      }

      val descriptions = nextDesc
      if (shell.settings.booleanSettingIsTrue("sortcolnames"))
        descriptions.sortWith(_.columnName < _.columnName)
      else
        descriptions.sortWith(_.ordinalPosition < _.ordinalPosition)
    }

    def printDescriptions(descriptions: List[ColumnInfo]): Unit = {
      require(descriptions != Nil)

      val width = max(descriptions.map(_.columnName.length): _*)
      val fmt = "%-" + width + "s  %s"
      // Note: Scala's format method doesn't left-justify.
      def formatter = new java.util.Formatter

      val colLines = descriptions.map(
        info => formatter.format(fmt, info.columnName, info.typeString)
      )

      // Use the table name returned from the driver.
      val header = "Table " + table
      val hr = "-" * header.length
      println(List(hr, header, hr) mkString "\n")
      println(colLines mkString ",\n")

      if (full) {
        // Map the table name to what the database engine thinks
        // the table's name should be. (Necessary for Oracle.)
        val first = descriptions(0)
        val schema = first.schema.getOrElse(null)
        findTableName(schema, table).foreach {
          tableName =>

            showExtraTableData(first.catalog.getOrElse(null),
                               schema, tableName)
        }
      }
    }

    def doDescribe(table: String, schema: Option[String]): Unit = {
      withSQLStatement(connection) {statement =>

        val metadata = connection.getMetaData
        val catalog = shell.settings.stringSetting("catalog", null)
        val jdbcSchema = schema.getOrElse(null)
        withResultSet(metadata.getColumns(catalog,
                                          jdbcSchema,
                                          table,
                                          null)) { rs =>

          // Everything should be in the same schema.
          columnDescriptions(rs) match {
            case Nil => logger.error("Can't get info for table " + table)
            case descriptions => printDescriptions(descriptions)
          }
        }
      }
    }

    val schema = shell.settings.stringSetting("schema")
    shell.tableSpec(table, schema) match {
      case None     => logger.error("No such table.")
      case Some(ts) => doDescribe(ts.name.get, schema)
    }

    KeepGoing
  }

  private def showExtraTableData(catalog: String,
                                 schema: String,
                                 table: String) = {
    val dmd = connection.getMetaData

    showPrimaryKeys(dmd, catalog, schema, table)
    showIndexes(dmd, catalog, schema, table)
    showConstraints(dmd, catalog, schema, table)
  }

  private def findTableName(schema: String, table: String): Option[String] = {
    val lcTable = table.toLowerCase
    val tables = shell.tables(schema)
    val matching = tables.filter(ts => (ts.name != None) &&
                                       (ts.name.get.toLowerCase == lcTable))

    matching.map(_.name.get) match {
      case tableName :: Nil =>
        Some(tableName)

      case tableName :: more =>
        logger.error("Too many tables match \"" + table + "\": ")
        logger.error(matching mkString ", ")
        None

      case Nil =>
        logger.error("No tables match \"" + table + "\"")
        None
    }
  }

  private def showPrimaryKeys(dmd: DatabaseMetaData,
                              catalog: String,
                              schema: String,
                              table: String) = {
    def getPrimaryKeyColumns(rs: ResultSet): List[String]  = {
      val result = new ListBuffer[String]
      while (rs.next)
      result += rs.getString("COLUMN_NAME")
      result.toList
    }

    try {
      logger.verbose("Getting primary keys for table " + table +
                     ", catalog " + catalog + ", schema " + schema)

      withResultSet(dmd.getPrimaryKeys(catalog, schema, table)) { rs =>

        val columns = getPrimaryKeyColumns(rs)
        if (columns != Nil)
          println("\nPrimary key columns: " + columns.mkString(", "))
      }
    }

    catch {
      case e: SQLException =>
        logger.error("Unable to retrieve primary key information: " +
                     e.getMessage)
    }
  }

  private def showIndexes(dmd: DatabaseMetaData,
                          catalog: String,
                          schema: String,
                          table: String) = {
    class IndexColumn(val columnName: String,
                      val unique: Boolean,
                      val ascending: Option[Boolean],
                      val indexType: String)

    val uniqueIndexes = MutableMap.empty[String, ArrayBuffer[IndexColumn]]
    val nonUniqueIndexes = MutableMap.empty[String,
                                            ArrayBuffer[IndexColumn]]

    @tailrec def gatherIndexInfo(rs: ResultSet): Unit = {
      if (rs.next) {
        val indexName = rs.getString("INDEX_NAME")
        val indexType = rs.getShort("TYPE") match {
          case DatabaseMetaData.tableIndexClustered =>
            "clustered"
          case DatabaseMetaData.tableIndexHashed =>
            "hashed"
          case DatabaseMetaData.tableIndexOther =>
            ""
          case _ if (indexName != null) =>
            ""
          case _ =>
            null
        }

        if (indexType != null) {
          val ascending = rs.getString("ASC_OR_DESC") match {
            case "A" => Some(true)
            case "D" => Some(false)
            case _   => None
          }

          val unique = ! rs.getBoolean("NON_UNIQUE")
          val column = rs.getString("COLUMN_NAME")

          val indexInfo = if (unique) uniqueIndexes
                          else nonUniqueIndexes
          if (! (indexInfo contains indexName))
            indexInfo += (indexName -> new ArrayBuffer[IndexColumn])

          indexInfo(indexName) += new IndexColumn(column, unique,
                                                  ascending, indexType)
        }

        gatherIndexInfo(rs)
      }
    }

    def printIndex(indexName: String, columns: List[IndexColumn]) {
      val buf = new StringBuilder
      val indexType =
        if (columns(0).indexType == null)
          null
        else
          columns(0).indexType.trim

      buf.append(indexName.trim)
      buf.append(": ")
      if (columns(0).unique)
        buf.append("Unique ")
      else
        buf.append("Non-unique ")

      if ((indexType != null) && (indexType != ""))
        buf.append(indexType + " index ")
      else
        buf.append("index ")

      buf.append("on (")
      buf.append(columns.map(_.columnName) mkString ", ")
      buf.append(")")
      wrapPrintln(buf.toString)
    }

    logger.verbose("Getting index information for table " + table +
                   ", catalog " + catalog + ", schema " + schema)
    try {

      val rs = dmd.getIndexInfo(catalog, schema, table, false, true)
      withResultSet(rs) { rs =>
        gatherIndexInfo(rs)
                       }

      if ((uniqueIndexes.size + nonUniqueIndexes.size) > 0) {
        println()
        for (indexName <- sortByName(uniqueIndexes.keysIterator))
          printIndex(indexName, uniqueIndexes(indexName).toList)
        for (indexName <- sortByName(nonUniqueIndexes.keysIterator))
          printIndex(indexName, nonUniqueIndexes(indexName).toList)
      }
    }

    catch {
      case e: SQLException =>
        logger.error("Unable to retrieve index information: " +
                     e.getMessage)
    }
  }

  private def showConstraints(dmd: DatabaseMetaData,
                              catalog: String,
                              schema: String,
                              table: String) = {
    def checkForNull(s: String): String = if (s == null) "?" else s

    @tailrec def printOne(rs: ResultSet): Unit = {
      if (rs.next) {
        val fk_name = checkForNull(rs.getString("FK_NAME"))
        printf("Foreign key %s: %s references %s.%s\n",
               fk_name,
               rs.getString("FKCOLUMN_NAME"),
               rs.getString("PKTABLE_NAME"),
               rs.getString("PKCOLUMN_NAME"));

        printOne(rs)
      }
    }

    try {
      logger.verbose("Getting constraint information for table " +
                     table + ", catalog " + catalog + ", schema " +
                     schema)
      val rs = dmd.getImportedKeys(catalog, schema, table)
      withResultSet(rs) { rs =>
        printOne(rs)
                       }
    }

    catch {
      case e: SQLException =>
        logger.error("Unable to retrieve constraint information: " +
                     e.getMessage)
    }
  }

  private def getJdbcTypeNames: Map[Int, String] = {
    import java.lang.reflect.Modifier

    // Get the list of static int fields

    val staticIntFields = classOf[SQLTypes].getFields.filter( f =>
      ((f.getModifiers & Modifier.STATIC) != 0) && (f.getType == classOf[Int])
    )

    // Create a map of int to name for those fields
    Map.empty[Int, String] ++
    staticIntFields.map(f => (f.getInt(null), f.getName))
  }
}

class ShowHandler(val shell: SQLShell, val connection: Connection)
extends SQLShellCommandHandler with Wrapper with Sorter {

  import grizzled.collection.GrizzledLinearSeq._

  val CommandName = ".show"
  val Help =
"""|Show various useful things.
   |
   |.show tables/<schema> [pattern]
   |.show tables [pattern]
   |
   |    Show table names. "pattern" is a regular expression that can be used
   |    to filter the table names. In the first form of the command, the schema
   |    name is supplied, and tables from that schema are listed. In the second
   |    form of the command, the schema is taken from the default schema.
   |
   |    See ".set schema" for more information.
   |
   | .show schemas
   |    Show the names of all schemas in the database.""".stripMargin

  private val subCommands = List("tables", "schemas")
  private val subCommandCompleter = new ListCompleter(subCommands,
                                                      _.toLowerCase)
  private val ShowTables = """^\s*tables(/[^/.\s]+)?\s*$""".r
  private val ShowSchemas = """^\s*(schemas)\s*$""".r
  private val PartialSubCommand = """^\s*([^\s]+)\s*$""".r

  def doRunCommand(commandName: String, args: String): CommandAction = {
    args.tokenize match {
      case ShowTables(schema) :: Nil => showTables(schema, ".*")
      case ShowTables(schema) :: pattern :: Nil => showTables(schema, pattern)
      case ShowSchemas(s) :: Nil => showSchemas
      case Nil => logger.error("Missing the things to show.")
      case _ => logger.error("Bad .show command: \"" + args + "\"")
    }

    KeepGoing
  }

  override def complete(token: String,
                        allTokens: List[CompletionToken],
                        line: String): List[String] = {
    allTokens match {
      case Nil =>
        assert(false) // shouldn't happen
         Nil

      case LineToken(cmd) :: Delim :: Cursor :: Nil =>
        // Command filled in (obviously, or we wouldn't be in here),
        // but first argument not.
        subCommandCompleter.complete(token, allTokens, line)

      case LineToken(cmd) :: Delim :: LineToken(ShowTables(s)) :: Cursor ::
           Nil =>
        Nil

      case LineToken(cmd) :: Delim :: LineToken(ShowTables(schema)) :: Delim ::
           Cursor :: Nil =>
        shell.matchTableNames(extractSchema(schema), None)

      case LineToken(cmd) :: Delim :: LineToken(ShowTables(schema)) :: Delim ::
           LineToken(tablePrefix) :: Cursor :: Nil =>
        shell.matchTableNames(extractSchema(schema), Some(tablePrefix))

      case LineToken(cmd) :: Delim :: LineToken(ShowSchemas(s)) :: Cursor ::
           Nil =>
        Nil

      case LineToken(cmd) :: Delim :: LineToken(ShowSchemas(s)) :: Delim ::
           Cursor :: Nil =>
        Nil

      case LineToken(cmd) :: Delim :: LineToken(arg) :: Cursor :: Nil =>
        subCommandCompleter.complete(token, allTokens, line)

      case LineToken(cmd) :: Delim :: LineToken(arg) :: Cursor :: Delim ::
           rest :: Nil =>
        subCommandCompleter.complete(token, allTokens, line)

      case _ =>
        Nil
    }
  }

  // Extract the schema from something matched by the ShowTables regex
  private def extractSchema(regexField: String): Option[String] = {
    if (regexField == null)
      None
    else if (regexField(0) == '/')
      Some(regexField drop 1)
    else
      Some(regexField)
  }

  private def showTables(schema: String, pattern: String) = {
    val nameFilter = new Regex("(?i)" + pattern) // case insensitive

    val schemaOption = schema match {
      case null                           => None
      case s: String if s.startsWith("/") => Some(s.substring(1))
      case _                              => Some(schema)
    }

    val tables: List[TableSpec] = shell.tables(schemaOption, nameFilter)
    val tableNames = tables.filter(_.name != None).map(_.name.get)
    val sorted = sortByName(tableNames)
    print(sorted.columnarize(shell.OutputWidth))

    KeepGoing
  }

  private def showSchemas = {
    val schemas = shell.getSchemas
    val sorted = sortByName(schemas)
    print(sorted.columnarize(shell.OutputWidth))
  }
}
