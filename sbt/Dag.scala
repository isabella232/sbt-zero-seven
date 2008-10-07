package sbt;

import scala.collection._;

trait Dag[Node <: Dag[Node]]{
	self : Node =>

	def dependencies : Iterable[Node]

	def topologicalSort = {
		val discovered = new mutable.HashSet[Node];
		val finished = new jcl.LinkedHashSet[Node];
		var time = 0;

		def visit(dag : Node){
			if (!discovered(dag)) {
				discovered(dag) = true; 
				dag.dependencies.foreach(visit);
				finished(dag) = true; 
			}
		}

		visit(self);
	
		finished.toList;
	}
}

