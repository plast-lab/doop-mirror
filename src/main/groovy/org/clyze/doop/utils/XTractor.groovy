package org.clyze.doop.utils

import org.clyze.doop.Main
import org.clyze.doop.core.Doop

class XTractor {
	static void main(String[] args) {
		Doop.initDoopFromEnv()
		def doopArgs = [
				"-i", new File(args[0]),
				"-a", "data-flow",
				"-id", "data-flow",
				"--extra-logic", "${Doop.souffleLogicPath}/addons/xtractor/analysis.dl",
				"--generate-tac"
		]
		doopArgs.addAll(args.drop(1))
		Main.main(doopArgs.toArray() as String[])


		def runDir = new File("${Doop.doopOut}/data-flow/database")
		def inFile = new File("$runDir/Schema_ClassInfo.csv")
		Map<String, List<String[]>> classInfo = [:].withDefault { [] }
		inFile.eachLine { line ->
			def (klass, kind, field, fieldType) = line.split("\t")
			classInfo[klass] << [kind, field, fieldType]
		}

		def outFile = new File(runDir, "xtractor-out.dl")
		outFile.text = ""

		def dlTypes = [] as Set
		def dlDecls = []
		def dlInputs = []
		def m = { def type ->
			switch (type) {
				case "int": return "number"
				case "java.lang.String": return "symbol"
				case "java.lang.String[]":
					dlTypes << ".type AR__symbol = [ head:symbol, rest:AR__symbol ]"
					return "AR__symbol"
				case ~/.*\[\]/:
					def t = type[0..-3]
					dlTypes << ".type AR_$t = [ head:$t, rest:AR_$t ]"
					return "AR_$t"
				default: return type
			}
		}

		classInfo.each { klass, relations ->
			dlTypes << ".type $klass"
			relations.each {
				def (kind, field, fieldType) = it
				String name
				switch (kind) {
					case "r":
						name = "${klass}_${field}"
						dlDecls << ".decl $name(this:$klass, $field:${m(fieldType)})"
						break
					case "R":
						name = "${klass}_CL_${field}"
						dlDecls << ".decl $name($field:${m(fieldType)})"
						break
					case "r[]":
						name = "${klass}_AR_${field}"
						dlDecls << ".decl $name(this:$klass, index:number, elem:${m(fieldType)})"
						break
					case "R[]":
						name = "${klass}_CL_AR_${field}"
						dlDecls << ".decl $name($field:${m(fieldType)})"
						break
					default:
						println "ERROR"
				}
				dlInputs << ".input $name"
			}
			def allFields = relations.findAll { it[0] == "r" }.collect { "${it[1]}:${m(it[2])}" }.join(", ")
			dlDecls << ".decl ${klass}_ALL(this:symbol, $allFields)"
			dlInputs << ".input ${klass}_ALL"
		}
		dlTypes.each { outFile << "$it\n" }
		dlDecls.each { outFile << "$it\n" }
		dlInputs.each { outFile << "$it\n" }

		println "Static Schema... Result in $outFile"
	}
}