package org.clyze.doop.core

import groovy.transform.CompileStatic
import groovy.transform.InheritConstructors
import groovy.transform.TypeChecked
import groovy.util.logging.Log4j
import org.clyze.doop.utils.SouffleScript

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import static org.apache.commons.io.FileUtils.*

@CompileStatic
@InheritConstructors
@Log4j
@TypeChecked
class SoufflePartitionedAnalysis extends SouffleAnalysis {

    @Override
    void run() {
        analysis = new File(outDir, "${name}.dl")
        deleteQuietly(analysis)
        analysis.createNewFile()

        initDatabase()
        basicAnalysis()
        if (!options.X_STOP_AT_BASIC.value) {
            mainAnalysis()
            produceStats()
        }

        def cacheDir = new File(Doop.souffleAnalysesCache, name)
        cacheDir.mkdirs()
        def script = new SouffleScript(executor)

        Future<File> compilationFuture = null
        def executorService = Executors.newSingleThreadExecutor()
        if (!options.X_STOP_AT_FACTS.value) {
            compilationFuture = executorService.submit(new Callable<File>() {
                @Override
                File call() {
                    log.info "[Task COMPILE...]"
                    def generatedFile = script.compile(analysis, outDir, cacheDir,
                            options.SOUFFLE_PROFILE.value as boolean,
                            options.SOUFFLE_DEBUG.value as boolean,
                            options.X_FORCE_RECOMPILE.value as boolean,
                            options.X_CONTEXT_REMOVER.value as boolean)
                    log.info "[Task COMPILE Done]"
                    return generatedFile
                }
            })
        }

        try {
            log.info "[Task FACTS...]"
            generateFacts()
            File destPartitionsFile = new File(factsDir, "TypeToPartition.facts")
            def lines = destPartitionsFile.readLines()

            def partitions = [] as Set<String>
            lines.each { String line ->
                String[] lineParts = line.split('\t')
                def partition = lineParts[1]
                partitions.add(partition)
            }

            int partitionNumber = 0
            partitions.each { partition ->
                partitionNumber++
                def childOutDir = new File(outDir.canonicalPath + "-part-" + partitionNumber)
                def childFactsDir = new File(childOutDir, "facts")

                childOutDir.mkdirs()
                childFactsDir.mkdirs()

                copyDirectory(factsDir, childFactsDir)
                new File(childFactsDir, "PrimaryPartition.facts").withWriter{ w ->
                    w.writeLine(partition)}
            }

            log.info "[Task FACTS Done]"

            if (options.X_STOP_AT_FACTS.value) return

            def generatedFile = compilationFuture.get()
            database.mkdirs()

            def analysisExecutorService = Executors.newFixedThreadPool(partitions.size())
            File runtimeMetricsFile = new File(database, "Stats_Runtime.csv")
            File statsMetricsFile = new File(database, "Stats_Metrics.csv")
            runtimeMetricsFile.createNewFile()
            statsMetricsFile.createNewFile()

            partitionNumber = 0
            partitions.each { partition ->
                partitionNumber++
                def childOutDir = new File(outDir.canonicalPath + "-part-" + partitionNumber)
                def childFactsDir = new File(childOutDir, "facts")

                analysisExecutorService.submit(new Runnable() {
                    @Override
                    void run() {
                        def childScript = new SouffleScript(executor)
                        childScript.run(generatedFile, childFactsDir, childOutDir, options.SOUFFLE_JOBS.value as int,
                                (options.X_MONITORING_INTERVAL.value as long) * 1000)
                        runtimeMetricsFile.append("analysis execution time (sec)\t${childScript.executionTime}\n")
                    }
                })
            }
            analysisExecutorService.shutdown()
            analysisExecutorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

            partitionNumber = 0
            def insVPT = 0
            def sensVPT = 0
            partitions.each { partition ->
                partitionNumber++
                def childDatabaseDir = new File(outDir.canonicalPath + "-part-" + partitionNumber + File.separator + "database")

                insVPT += Files.lines(new File(childDatabaseDir, "Stats_Simple_InsensVarPointsTo.csv").toPath()).count()
                sensVPT += Files.lines(new File(childDatabaseDir, "Stats_Simple_PrimaryVarPointsTo.csv").toPath()).count()
            }
            statsMetricsFile.append("1.0\tvar points-to (INS)\t${insVPT}\n")
            statsMetricsFile.append("1.5\tvar points-to (SENS)\t${sensVPT}\n")

            int dbSize = (sizeOfDirectory(database) / 1024).intValue()

            runtimeMetricsFile.append("analysis compilation time (sec)\t${script.compilationTime}\n")
            runtimeMetricsFile.append("disk footprint (KB)\t$dbSize\n")
            runtimeMetricsFile.append("soot-fact-generation time (sec)\t$factGenTime\n")
        } finally {
            executorService.shutdownNow()
        }
    }
}
