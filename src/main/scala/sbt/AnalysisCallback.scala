/* sbt -- Simple Build Tool
 * Copyright 2008, 2009  Mark Harrah
 */
package sbt

import java.io.File

object AnalysisCallback
{
	private val map = new scala.collection.mutable.HashMap[Int, AnalysisCallback]
	private var nextID: Int = 0
	def register(callback: AnalysisCallback): Int =
	{
		val id = nextID
		nextID += 1
		map(id) = callback
		id
	}
	def apply(id: Int): Option[AnalysisCallback] = map.get(id)
	def unregister(id: Int)
	{
		map -= id
	}
}

trait AnalysisCallback extends NotNull
{
	/** The names of classes that the analyzer should find subclasses of.*/
	def superclassNames: Iterable[String]
	/** The base path for the project.*/
	def basePath: Path
	/** Called when the the given superclass could not be found on the classpath by the compiler.*/
	def superclassNotFound(superclassName: String): Unit
	/** Called before the source at the given location is processed. */
	def beginSource(sourcePath: Path): Unit
	/** Called when the a subclass of one of the classes given in <code>superclassNames</code> is
	* discovered.*/
	def foundSubclass(sourcePath: Path, subclassName: String, superclassName: String, isModule: Boolean): Unit
	/** Called to indicate that the source file <code>sourcePath</code> depends on the source file
	* <code>dependsOnPath</code>.*/
	def sourceDependency(dependsOnPath: Path, sourcePath: Path): Unit
	/** Called to indicate that the source file <code>sourcePath</code> depends on the jar
	* <code>jarPath</code>.*/
	def jarDependency(jarPath: File, sourcePath: Path): Unit
	/** Called to indicate that the source file <code>sourcePath</code> depends on the class file
	* <code>classFile</code>.*/
	def classDependency(classFile: File, sourcePath: Path): Unit
	/** Called to indicate that the source file <code>sourcePath</code> produces a class file at
	* <code>modulePath</code>.*/
	def generatedClass(sourcePath: Path, modulePath: Path): Unit
	/** Called after the source at the given location has been processed. */
	def endSource(sourcePath: Path): Unit
}
abstract class BasicAnalysisCallback(val basePath: Path, val superclassNames: Iterable[String],
	protected val analysis: CompileAnalysis) extends AnalysisCallback
{
	def superclassNotFound(superclassName: String) {}
	
	def beginSource(sourcePath: Path)
	{
		analysis.addSource(sourcePath)
	}
	def sourceDependency(dependsOnPath: Path, sourcePath: Path)
	{
		analysis.addSourceDependency(dependsOnPath, sourcePath)
	}
	def jarDependency(jarFile: File, sourcePath: Path)
	{
		analysis.addExternalDependency(jarFile, sourcePath)
	}
	def classDependency(classFile: File, sourcePath: Path)
	{
		analysis.addExternalDependency(classFile, sourcePath)
	}
	def generatedClass(sourcePath: Path, modulePath: Path)
	{
		analysis.addProduct(sourcePath, modulePath)
	}
	def endSource(sourcePath: Path)
	{
		analysis.removeSelfDependency(sourcePath)
	}
}