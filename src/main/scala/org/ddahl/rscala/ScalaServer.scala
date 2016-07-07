package org.ddahl.rscala

import java.net._
import java.io._
import scala.annotation.tailrec

import Protocol._

class ScalaServer private (repl: InterpreterAdapter, portsFilename: String, debugger: Debugger, private var serializeOutput: Boolean) {

  private val sockets = new ScalaSockets(portsFilename,debugger)
  import sockets.{in, out, socketIn, socketOut}
  private val R = org.ddahl.rscala.RClient(this,in,out,debugger,serializeOutput)

  if ( repl == null ) {
    out.writeInt(ERROR)
    out.flush
    sys.error("REPL is null.")
  } else {
    out.writeInt(OK)
    out.flush
    repl.bind("R","org.ddahl.rscala.RClient",R)
    if ( repl.interpreter.isInstanceOf[scala.tools.nsc.interpreter.IMain] ) {
      repl.bind("$intp","scala.tools.nsc.interpreter.IMain",repl.interpreter)
      repl.eval("import org.ddahl.rscala.RObject")
    }
  }

  private var functionResult: (Any, String) = null
  private var wrapResult: Array[Array[String]] = null
  private var cacheMap = new scala.collection.mutable.ArrayBuffer[(Any,String)]()
  private val cacheMapExtractor = """\.(\d+)""".r
  private val functionParametersStack = new scala.collection.mutable.ArrayBuffer[Any]()
  private val functionMap = new scala.collection.mutable.HashMap[String,(Any,java.lang.reflect.Method,Int,String)]()

  private def extractReturnType(signature: String) = {
    var squareCount = 0
    var parenCount = 0
    var i = 0
    val s = signature.trim
    while ( ( ( parenCount > 0 ) || ( squareCount > 0 ) || ( ! ( ( s(i) == '=' ) && ( s(i+1) == '>' ) ) ) ) && ( i < s.length ) ) {
      val c = s(i)
      i += 1
      if ( c == '(' ) parenCount += 1
      if ( c == ')' ) parenCount -= 1
      if ( c == '[' ) squareCount += 1
      if ( c == ']' ) squareCount -= 1
    }
    if ( i == s.length ) throw new IllegalArgumentException("Unexpected end of signature."+signature)
    i += 2
    val r = s.substring(i).trim
    if ( r.length == 0 ) throw new IllegalArgumentException("Unexpected end of signature."+signature)
    r
  }

  private def storeFunction(functionName: String, nArgs: Int): Unit = {
    val f = repl.valueOfTerm(functionName).get
    val functionType = repl.typeOfTerm(functionName)
    val returnType = extractReturnType(functionType)
    val m = f.getClass.getMethods.filter(_.getName == "apply") match {
      case Array() => null
      case Array(method) => method
      case Array(method1,method2) => method1
      case _ => null
    }
    functionMap(functionName) = (f,m,nArgs,returnType)
  }

  private def callFunction(functionName: String): Any = {
    val (f,m,nArgs,resultType) = functionMap(functionName)
    val functionParameters = functionParametersStack.takeRight(nArgs)
    functionParametersStack.trimEnd(nArgs)
    if ( debugger.debug ) {
      debugger.msg("Function is: "+f)
      debugger.msg("There are "+functionParameters.length+" parameters.")
      debugger.msg("<<"+functionParameters.mkString(">>\n<<")+">>")
    }
    functionResult = (m.invoke(f, functionParameters.map(_.asInstanceOf[AnyRef]): _*), resultType)
  }

  private def readString() = Helper.readString(in)
  private def writeString(string: String): Unit = {
    if ( debugger.debug ) debugger.msg("Writing string: <"+string+">")
    Helper.writeString(out,string)
  }

  private def setAVM(identifier: String, t: String, v: Any): Unit = {
    if ( debugger.debug ) debugger.msg("Value is "+v)
    if ( identifier == "." ) functionParametersStack.append(v)
    else repl.bind(identifier,t,v)
  }

  private val sep = sys.props("line.separator")

  private def doGC(): Unit = {
    if ( debugger.debug ) debugger.msg("Garbage collection")
    val length = in.readInt()
    if ( debugger.debug ) debugger.msg("... of length: "+length)
    for ( i <- 0 until length ) {
      cacheMap(in.readInt()) = null
    }
  }

  private def doEval(): Unit = {
    val snippet = readString()
    try {
      repl.eval(snippet)
      R.exit()
      if ( debugger.debug ) debugger.msg("Eval is okay")
      out.writeInt(OK)
    } catch {
      case e: Throwable =>
        if ( debugger.debug ) debugger.msg("Caught throwable: "+e.toString)
        repl.bind("$rscalamxception","Throwable",e) // So that repl.mostRecentVar is this throwable
        R.exit()
        out.writeInt(ERROR)
        e.printStackTrace()
        scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
    }
  }

  private def doDef(): Unit = {
    val args = readString().trim
    val body = readString()
    if ( debugger.debug ) debugger.msg("Got arguments and body.")
    try {
      val params = if ( args != "" ) {
        val y = args.split(":").map(z => {
          val l = z.lastIndexOf(",")
          if ( l >= 0 ) Array(z.substring(0,l).trim,z.substring(l+1).trim)
          else Array(z.trim)
        }).flatten
        val l = y.length/2
        val r = new Array[String](l)
        val s = new Array[String](l)
        for ( i <- 0 until l ) {
          r(i) = y(2*i)
          s(i) = y(2*i+1)
        }
        (r,s)
      } else (Array[String](),Array[String]())
      if ( debugger.debug ) debugger.msg("Parsed arguments.")
      out.writeInt(OK)
      try {
        val paramNames = params._1
        val paramTypes = params._2
        out.writeInt(OK)
        try {
          repl.eval(s"($args) => { $body }")
          out.writeInt(OK)
          val functionName = repl.mostRecentVar
          if ( debugger.debug ) debugger.msg("Name of function is: <"+functionName+">")
          try {
            storeFunction(functionName,paramNames.length)
            if ( debugger.debug ) debugger.msg("Stored function.")
            out.writeInt(OK)
            writeString(functionName)
            if ( debugger.debug ) debugger.msg("Everything is okay in 'def'.")
            out.writeInt(paramNames.length)
            if ( debugger.debug ) debugger.msg("There are "+paramNames.length+" parameters.")
            paramNames.foreach(writeString)
            paramTypes.foreach(writeString)
            writeString(functionMap(functionName)._4)
            if ( debugger.debug ) debugger.msg("Done.")
          } catch {
            case e: Throwable =>
              out.writeInt(ERROR)
              e.printStackTrace()
              scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
          }
        } catch {
          case e: Throwable =>
            out.writeInt(ERROR)
            // e.printStackTrace()
            scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
        }
      } catch {
        case e: Throwable =>
          out.writeInt(ERROR)
      }
    } catch {
      case e: Throwable =>
        out.writeInt(ERROR)
    }
  }

  private def doInvoke(): Unit = {
    val functionName = readString()
    try {
      val f = repl.valueOfTerm(functionName).get
      callFunction(functionName)
      R.exit()
      if ( debugger.debug ) debugger.msg("Invoke is okay")
      out.writeInt(OK)
    } catch {
      case e: Throwable =>
        R.exit()
        out.writeInt(ERROR)
        // e.printStackTrace()
        scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
    }
  }

  private val RegexpVar = """^\s+(?:final )?\s*(?:override )?\s*var\s+([^(:]+):\s+([^=;]+).*""".r
  private val RegexpVal = """^\s+(?:final )?\s*(?:override )?\s*val\s+([^(:]+):\s+([^=;]+).*""".r
  private val RegexpDefNoParen = """^\s+(?:final )?\s*(?:override)?\s*def\s+([a-zA-Z0-9]+)(?:\[.*\]:|:)\s+([^=;]+).*""".r
  private val RegexpDefWithParen = """^\s+(?:final )?\s*(?:override)?\s*def\s+([a-zA-Z0-9]+)(?:\[.*\]+)?\((.*)\):\s+([^=]+).*""".r
  private val RegexpConstructor = """^\s+def\s+this\((.*)\).*""".r
  private val RegexpThrows = """^\s*throws\s+.*""".r
  private val rscalaReference = "rscalaReference"

  private def parseArgNames(args: String): Array[String] = {
    if ( args.contains(")(") ) null
    else {
      val x = args.split(":")
      val y = if ( x.length > 1 ) x.dropRight(1) else x
      y.map(z => {
        val i = z.lastIndexOf(",")
        if ( i >= 0 ) z.substring(i+1).trim else z.trim
      })
    }
  }

  private def doScalap(): Unit = {
    if ( debugger.debug ) debugger.msg("Doing scalap...")
    val itemName = readString()
    if ( debugger.debug ) debugger.msg("... on "+itemName)
    val classpath = sys.props("sun.boot.class.path") + sys.props("path.separator") + sys.props("rscala.classpath")
    if ( debugger.debug ) debugger.msg("... with classpath "+classpath)
    scala.tools.scalap.Main.main(Array("-cp",classpath,itemName))
  }

  private def doSet(): Unit = {
    val identifier = readString()
    in.readInt() match {
      case NULLTYPE =>
        if ( debugger.debug ) debugger.msg("Setting null.")
        if ( identifier == "." ) functionParametersStack.append(null)
        else repl.bind(identifier,"Any",null)
      case REFERENCE =>
        if ( debugger.debug ) debugger.msg("Setting reference.")
        val originalIdentifier = readString()
        try {
          if ( identifier == "." ) originalIdentifier match {
            case "null" =>
              functionParametersStack.append(null)
            case cacheMapExtractor(i) =>
              functionParametersStack.append(cacheMap(i.toInt)._1)
            case _ =>
              functionParametersStack.append(repl.valueOfTerm(originalIdentifier).get)
          } else {
            val r = identifier.split(":") match {
              case Array(n)   => (n.trim,"")
              case Array(n,t) => (n.trim,t.trim)
            }
            originalIdentifier match {
              case cacheMapExtractor(i) =>
                val (originalValue,originalType) = cacheMap(i.toInt)
                repl.bind(r._1,originalType,originalValue)
              case _ =>
                val vt = if ( r._2 == "" ) r._1 else r._1 + ": " + r._2
                repl.eval(s"val ${vt} = ${originalIdentifier}")
            }
          }
          out.writeInt(OK)
        } catch {
          case e: Throwable =>
            if ( debugger.debug ) debugger.msg("Caught exception: "+e)
            out.writeInt(ERROR)
            e.printStackTrace()
            scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
        }
      case ATOMIC =>
        if ( debugger.debug ) debugger.msg("Setting atomic.")
        val (v: Any, t: String) = in.readInt() match {
          case INTEGER => (in.readInt(),"Int")
          case DOUBLE => (in.readDouble(),"Double")
          case BOOLEAN => (( in.readInt() != 0 ),"Boolean")
          case STRING => (readString(),"String")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case VECTOR =>
        if ( debugger.debug ) debugger.msg("Setting vector...")
        val length = in.readInt()
        if ( debugger.debug ) debugger.msg("... of length: "+length)
        val (v, t): (Any,String) = in.readInt() match {
          case INTEGER => (Array.fill(length) { in.readInt() },"Array[Int]")
          case DOUBLE => (Array.fill(length) { in.readDouble() },"Array[Double]")
          case BOOLEAN => (Array.fill(length) { ( in.readInt() != 0 ) },"Array[Boolean]")
          case STRING => (Array.fill(length) { readString() },"Array[String]")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case MATRIX =>
        if ( debugger.debug ) debugger.msg("Setting matrix...")
        val nrow = in.readInt()
        val ncol = in.readInt()
        if ( debugger.debug ) debugger.msg("... of dimensions: "+nrow+","+ncol)
        val (v, t): (Any,String) = in.readInt() match {
          case INTEGER => (Array.fill(nrow) { Array.fill(ncol) { in.readInt() } },"Array[Array[Int]]")
          case DOUBLE => (Array.fill(nrow) { Array.fill(ncol) { in.readDouble() } },"Array[Array[Double]]")
          case BOOLEAN => (Array.fill(nrow) { Array.fill(ncol) { ( in.readInt() != 0 ) } },"Array[Array[Boolean]]")
          case STRING => (Array.fill(nrow) { Array.fill(ncol) { readString() } },"Array[Array[String]]")
          case _ => throw new RuntimeException("Protocol error")
        }
        setAVM(identifier,t,v)
      case _ => throw new RuntimeException("Protocol error")
    }
  }

  private def doGet(): Unit = {
    val identifier = readString()
    if ( debugger.debug ) debugger.msg("Trying to get value of: "+identifier)
    val optionWithType = try {
      identifier match {
        case "." => 
          val mrv = repl.mostRecentVar
          (repl.valueOfTerm(mrv),repl.typeOfTerm(mrv))
        case "?" => 
          (Some(functionResult._1),functionResult._2)
        case cacheMapExtractor(i) =>
          val tuple = cacheMap(i.toInt)
          (Some(tuple._1),tuple._2)
        case "null" =>
          (Some(null),"Null")
        case _ =>
          (repl.valueOfTerm(identifier),repl.typeOfTerm(identifier))
      }
    } catch {
      case e: Throwable =>
        if ( debugger.debug ) debugger.msg("Caught exception: "+e)
        out.writeInt(ERROR)
        e.printStackTrace()
        scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
        return
    }
    if ( debugger.debug ) debugger.msg("Getting: "+identifier)
    if ( optionWithType._1.isEmpty ) {
      if ( debugger.debug ) debugger.msg("... which does not exist.")
      out.writeInt(UNDEFINED_IDENTIFIER)
      return
    }
    val v = optionWithType._1.get
    if ( v == null || v.isInstanceOf[Unit] ) {
      if ( debugger.debug ) debugger.msg("... which is null")
      out.writeInt(NULLTYPE)
      return
    }
    if ( optionWithType._2 == "org.ddahl.rscala.RObject" ) {
      out.writeInt(REFERENCE)
      writeString(v.asInstanceOf[org.ddahl.rscala.RObject].name)
      return
    }
    val t = if ( optionWithType._2 == "Any" ) {
      val c = v.getClass
      if ( debugger.debug ) debugger.msg("Trying to infer type of "+c)
      if ( c.isArray ) {
        c.getName match {
          case "[I" => "Array[Int]"
          case "[D" => "Array[Double]"
          case "[Z" => "Array[Boolean]"
          case "[Ljava.lang.String;" => "Array[String]"
          case "[[I" => "Array[Array[Int]]"
          case "[[D" => "Array[Array[Double]]"
          case "[[Z" => "Array[Array[Boolean]]"
          case "[[Ljava.lang.String;" => "Array[Array[String]]"
          case _ => "Any"
        }
      } else {
        c.getName match {
          case "java.lang.Integer" => "Int"
          case "java.lang.Double" => "Double"
          case "java.lang.Boolean" => "Boolean"
          case "java.lang.String" => "String"
          case _ => "Any"
        }
      }
    } else optionWithType._2
    if ( debugger.debug ) debugger.msg("... whose type is: "+t)
    if ( debugger.debug ) debugger.msg("... and whose value is: "+v)
    t match {
      case "Array[Int]" =>
        val vv = v.asInstanceOf[Array[Int]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(INTEGER)
        for ( i <- 0 until vv.length ) out.writeInt(vv(i))
      case "Array[Double]" =>
        val vv = v.asInstanceOf[Array[Double]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(DOUBLE)
        for ( i <- 0 until vv.length ) out.writeDouble(vv(i))
      case "Array[Boolean]" =>
        val vv = v.asInstanceOf[Array[Boolean]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(BOOLEAN)
        for ( i <- 0 until vv.length ) out.writeInt(if ( vv(i) ) 1 else 0)
      case "Array[String]" =>
        val vv = v.asInstanceOf[Array[String]]
        out.writeInt(VECTOR)
        out.writeInt(vv.length)
        out.writeInt(STRING)
        for ( i <- 0 until vv.length ) writeString(vv(i))
      case "Array[Array[Int]]" =>
        val vv = v.asInstanceOf[Array[Array[Int]]]
        if ( Helper.isMatrix(vv) ) {
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(INTEGER)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vvv.length ) {
              out.writeInt(vv(i)(j))
            }
          }
        }
      case "Array[Array[Double]]" =>
        val vv = v.asInstanceOf[Array[Array[Double]]]
        if ( Helper.isMatrix(vv) ) {
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(DOUBLE)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vvv.length ) {
              out.writeDouble(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[Boolean]]" =>
        val vv = v.asInstanceOf[Array[Array[Boolean]]]
        if ( Helper.isMatrix(vv) ) {
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(BOOLEAN)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vv(i).length ) {
              out.writeInt(if ( vvv(j) ) 1 else 0)
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Array[Array[String]]" =>
        val vv = v.asInstanceOf[Array[Array[String]]]
        if ( Helper.isMatrix(vv) ) {
          out.writeInt(MATRIX)
          out.writeInt(vv.length)
          if ( vv.length > 0 ) out.writeInt(vv(0).length)
          else out.writeInt(0)
          out.writeInt(STRING)
          for ( i <- 0 until vv.length ) {
            val vvv = vv(i)
            for ( j <- 0 until vv(i).length ) {
              writeString(vvv(j))
            }
          }
        } else out.writeInt(UNSUPPORTED_STRUCTURE)
      case "Int" =>
        out.writeInt(ATOMIC)
        out.writeInt(INTEGER)
        out.writeInt(v.asInstanceOf[Int])
      case "Double" =>
        out.writeInt(ATOMIC)
        out.writeInt(DOUBLE)
        out.writeDouble(v.asInstanceOf[Double])
      case "Boolean" =>
        out.writeInt(ATOMIC)
        out.writeInt(BOOLEAN)
        out.writeInt(if (v.asInstanceOf[Boolean]) 1 else 0)
      case "String" =>
        out.writeInt(ATOMIC)
        out.writeInt(STRING)
        writeString(v.asInstanceOf[String])
      case _ =>
        if ( debugger.debug ) debugger.msg("Bowing out: "+identifier)
        out.writeInt(UNSUPPORTED_STRUCTURE)
    }
  }

  private def doGetReference(): Unit = {
    val identifier = readString()
    if ( debugger.debug ) debugger.msg("Trying to get reference for: "+identifier)
    identifier match {
      case "?" =>
        cacheMap.append(functionResult)
        out.writeInt(OK)
        writeString("." + (cacheMap.length-1))
        writeString(functionResult._2)
      case cacheMapExtractor(i) =>
        out.writeInt(OK)
        writeString(identifier)
        val r = cacheMap(i.toInt)
        writeString(r.getClass.getName)
      case _ =>
        if ( identifier == "null" ) {
          out.writeInt(OK)
          writeString("null")
          writeString("Null")
          return
        }
        val id = if ( identifier == "." ) repl.mostRecentVar else identifier
        val option = try {
          repl.valueOfTerm(id)
        } catch {
          case e: Throwable =>
            if ( debugger.debug ) debugger.msg("Caught exception: "+e)
            out.writeInt(ERROR)
            e.printStackTrace()
            scala.Console.err.println("Exception message: "+(if (e.getCause != null) e.getCause.getMessage else e.getMessage)+System.lineSeparator)
            return
        }
        if ( option.isEmpty ) out.writeInt(UNDEFINED_IDENTIFIER)
        else {
          out.writeInt(OK)
          writeString(id)
          writeString(repl.typeOfTerm(id))
        }
    }
  }

  private val originalOut = java.lang.System.out
  private val originalErr = java.lang.System.err

  private def heart(request: Int): Boolean = {
    if ( debugger.debug ) debugger.msg("Received request: "+request)
    request match {
      case SHUTDOWN =>
        if ( debugger.debug ) debugger.msg("Shutting down")
        in.close()
        out.close()
        socketIn.close()
        socketOut.close()
        return false
      case EXIT =>
        if ( debugger.debug ) debugger.msg("Exiting")
        return false
      case RESET =>
        if ( debugger.debug ) debugger.msg("Resetting")
        cacheMap.clear()
      case GC =>
        doGC()
      case DEBUG =>
        val newDebug = ( in.readInt() != 0 )
        if ( debugger.debug != newDebug ) debugger.msg("Debugging is now "+newDebug)
        debugger.debug = newDebug
      case SERIALIZE =>
        serializeOutput = ( in.readInt() != 0 )
        R._serializeOutput = serializeOutput
        if ( debugger.debug ) debugger.msg("Serialize output is now "+serializeOutput)
      case EVAL =>
        doEval()
      case SET =>
        doSet()
      case GET =>
        doGet()
      case GET_REFERENCE =>
        doGetReference()
      case DEF =>
        doDef()
      case INVOKE =>
        doInvoke()
      case SCALAP =>
        doScalap()
    }
    true
  }

  final def run(): Unit = {
    while ( true ) {
      if ( debugger.debug ) debugger.msg("Scala server at top of the loop waiting for a command.")
      val request = try {
        in.readInt()
      } catch {
        case _: Throwable =>
          return
      }
      if ( serializeOutput ) {
        val baosOut = new java.io.ByteArrayOutputStream()
        val baosErr = new java.io.ByteArrayOutputStream()
        val psOut = new java.io.PrintStream(baosOut,true)
        val psErr = new java.io.PrintStream(baosErr,true)
        System.setOut(psOut)
        System.setErr(psErr)
        scala.Console.withOut(psOut) {
          scala.Console.withErr(psErr) {
            if ( ! heart(request) ) return
          }
        }
        writeString(baosOut.toString+baosErr.toString)
      } else {
        if ( ! heart(request) ) return
      }
      out.flush()
    }
  }

}

object ScalaServer {

  def apply(repl: InterpreterAdapter, portsFilename: String, debug: Boolean = false, serializeOutput: Boolean = false): ScalaServer = new ScalaServer(repl, portsFilename, new Debugger(System.out,"Scala",false,debug), serializeOutput)

}
