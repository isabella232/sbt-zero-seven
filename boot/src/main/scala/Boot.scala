/* sbt -- Simple Build Tool
 * Copyright 2009 Mark Harrah
 */
 package sbt.boot

// This is the main class for the sbt launcher.  Its purpose is to ensure the appropriate
// versions of sbt and scala are downloaded to the projects 'project/boot' directory.
// Then, the downloaded version of sbt is started as usual using the right version of
// scala.

// Artifact names must be consistent between the main sbt build and this build.

import java.io.{File, FileFilter}
import java.net.{MalformedURLException, URL, URLClassLoader}

// contains constants and paths
import BootConfiguration._
import UpdateTarget.{UpdateScala, UpdateSbt}

// The exception to use when an error occurs at the launcher level (and not a nested exception).
// This indicates overrides toString because the exception class name is not needed to understand
// the error message.
private class BootException(override val toString: String) extends RuntimeException
// The entry point to the launcher
object Boot
{
	def main(args: Array[String])
	{
		checkProxy()
		try { boot(args) }
		catch
		{
			case b: BootException => errorAndExit(b)
			case e =>
				e.printStackTrace
				errorAndExit(e)
		}
		System.exit(0)
	}
	private def errorAndExit(e: Throwable)
	{
		System.out.println("Error during sbt execution: " + e.toString)
		System.exit(1)
	}
	def boot(args: Array[String])
	{
		 // prompt to create project if it doesn't exist.
		 // will not return if user declines
		(new Setup).checkProject()
		if(args.length == 0)
			load(args) // interactive mode, which can only use one version of scala for a run
		else
			runBatch(args.toList, Nil)  // batch mode, which can reboot with a different scala version
	}
	private def runBatch(args: List[String], accumulateReversed: List[String])
	{
		def doLoad() = if(!accumulateReversed.isEmpty) load(accumulateReversed.reverse.toArray)
		args match
		{
			case Nil => doLoad()
			case RebootCommand :: tail =>
				doLoad()
				runBatch(tail, Nil)
			case notReload :: tail => runBatch(tail, notReload :: accumulateReversed)
		}
	}
	/** Loads the project in the current working directory using the version of scala and sbt
	* declared in the build. The class loader used prevents the Scala and Ivy classes used by
	* this loader from being seen by the loaded sbt/project.*/
	private def load(args: Array[String])
	{
		val loader = (new Setup).loader()
		val sbtMain = Class.forName(SbtMainClass, true, loader)
		val exitCode = run(sbtMain, args)
		if(exitCode == NormalExitCode)
			()
		else if(exitCode == RebootExitCode)
			load(args)
		else
			System.exit(exitCode)
	}
	private def run(sbtMain: Class[_], args: Array[String]): Int =
	{
		try {
			// Versions newer than 0.3.8 enter through the run method, which does not call System.exit
			val runMethod = sbtMain.getMethod(MainMethodName, classOf[Array[String]])
			runMethod.invoke(null, Array(args) : _*).asInstanceOf[Int]
		} catch {
			case e: NoSuchMethodException => runOld(sbtMain, args)
		}
	}
	/** The entry point for version 0.3.8 was the main method. */
	private def runOld(sbtMain: Class[_], args: Array[String]): Int =
	{
		val runMethod = sbtMain.getMethod(OldMainMethodName, classOf[Array[String]])
		runMethod.invoke(null, Array(args) : _*)
		NormalExitCode
	}
	
	private def checkProxy()
	{
		import ProxyProperties._
		val httpProxy = System.getenv(HttpProxyEnv)
		if(isDefined(httpProxy) && !isPropertyDefined(ProxyHost) && !isPropertyDefined(ProxyPort))
		{
			try
			{
				val proxy = new URL(httpProxy)
				setProperty(ProxyHost, proxy.getHost)
				val port = proxy.getPort
				if(port >= 0)
					System.setProperty(ProxyPort, port.toString)
				copyEnv(HttpProxyUser, ProxyUser)
				copyEnv(HttpProxyPassword, ProxyPassword)
			}
			catch
			{
				case e: MalformedURLException =>
					System.out.println("Warning: could not parse http_proxy setting: " + e.toString)
			}
		}
	}
	private def copyEnv(envKey: String, sysKey: String) { setProperty(sysKey, System.getenv(envKey)) }
	private def setProperty(key: String, value: String) { if(value != null) System.setProperty(key, value) }
	private def isPropertyDefined(k: String) = isDefined(System.getProperty(k))
	private def isDefined(s: String) = s != null && !s.isEmpty
}

/** A class to handle setting up the properties and classpath of the project
* before it is loaded. */
private class Setup extends NotNull
{
	import Setup._
	private val ProjectDirectory = new File(ProjectDirectoryName)
	private val BootDirectory = new File(ProjectDirectory, BootDirectoryName)
	private val PropertiesFile = new File(ProjectDirectory, BuildPropertiesName)
	
	final def checkProject()
	{
		if(!ProjectDirectory.exists)
		{
			val line = SimpleReader.readLine("Project does not exist, create new project? (y/N) : ")
			if(isYes(line))
				ProjectProperties(PropertiesFile, true)
			else
				System.exit(1)
		}
	}
	
	/** Checks that the requested version of sbt and scala have been downloaded.
	* It performs a simple check that the appropriate directories exist.  It does
	* not actually verify that appropriate classes are resolvable.  It uses Ivy
	* to resolve and retrieve any necessary libraries. The classpath to use is returned.*/
	final def loader(): ClassLoader = loader(Nil)
	private final def loader(forcePrompt: Seq[String]): ClassLoader =
	{
		val (scalaVersion, sbtVersion) = ProjectProperties.forcePrompt(PropertiesFile, forcePrompt : _*)
		
		val baseDirectory = new File(BootDirectory, baseDirectoryName(scalaVersion))
		System.setProperty(ScalaHomeProperty, baseDirectory.getAbsolutePath)
		val scalaDirectory = new File(baseDirectory, ScalaDirectoryName)
		val sbtDirectory = new File(baseDirectory, sbtDirectoryName(sbtVersion))
		
		val updateTargets = needsUpdate("", scalaDirectory, TestLoadScalaClasses, UpdateScala) ::: needsUpdate(sbtVersion, sbtDirectory, TestLoadSbtClasses, UpdateSbt)
		Update(baseDirectory, sbtVersion, scalaVersion, updateTargets: _*)
		
		import ProjectProperties.{ScalaVersionKey, SbtVersionKey}
		val sbtFailed = failIfMissing(sbtDirectory, TestLoadSbtClasses, "sbt " + sbtVersion, SbtVersionKey)
		val scalaFailed = failIfMissing(scalaDirectory, TestLoadScalaClasses, "Scala " + scalaVersion, ScalaVersionKey)
		
		(scalaFailed +++ sbtFailed) match
		{
			case Success =>
				val classpath = getJars(scalaDirectory, sbtDirectory)
				new URLClassLoader(classpath.toArray, new BootFilteredLoader)
			case f: Failure =>
				val noRetrieveMessage = "Could not retrieve " + f.label + "."
				val getNewVersions = SimpleReader.readLine(noRetrieveMessage + " Select different version? (y/N) : ")
				if(isYes(getNewVersions))
					loader(f.keys)
				else
					throw new BootException(noRetrieveMessage)
		}
	}
}
private object Setup
{
	private def failIfMissing(dir: File, classes: Iterable[String], label: String, key: String) = checkTarget(dir, classes, Success, new Failure(label, List(key)))
	private def needsUpdate(version: String, dir: File, classes: Iterable[String], target: UpdateTarget.Value) =
		if(version.endsWith("-SNAPSHOT"))
			target :: Nil
		else
			checkTarget(dir, classes, Nil, target :: Nil)
	private def checkTarget[T](dir: File, classes: Iterable[String], ifSuccess: => T, ifFailure: => T): T =
	{
		if(dir.exists)
		{
			val loader = new URLClassLoader(getJars(dir).toArray, new BootFilteredLoader)
			try
			{
				for(c <- classes)
					Class.forName(c, false, loader)
				ifSuccess
			}
			catch { case e: ClassNotFoundException => ifFailure }
		}
		else
			ifFailure
	}
	private def isYes(so: Option[String]) =
		so match
		{
			case Some(s) =>
				val check = s.toLowerCase
				check == "y" || check == "yes"
			case None => false
		}
	private def getJars(directories: File*) = directories.flatMap(file => wrapNull(file.listFiles(JarFilter))).map(_.toURI.toURL)
	private def wrapNull(a: Array[File]): Array[File] = if(a == null) Array() else a
}

private object JarFilter extends FileFilter
{
	def accept(file: File) = !file.isDirectory && file.getName.endsWith(".jar")
}

private sealed trait Checked extends NotNull { def +++(o: Checked): Checked }
private final object Success extends Checked { def +++(o: Checked) = o }
private final class Failure(val label: String, val keys: List[String]) extends Checked
{
	def +++(o: Checked) =
		o match
		{
			case Success => this
			case f: Failure => new Failure(label + " and " + f.label, keys ::: f.keys)
		}
}