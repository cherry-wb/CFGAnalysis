package de.fosd.typechef.cfganalysis

import io.Source
import java.io.{FileInputStream, FileWriter, File}
import de.fosd.typechef.featureexpr.{FeatureExprParser, FeatureExprFactory}
import de.fosd.typechef.typesystem.linker.SystemLinker

/**
 * currently here to avoid complication with rebuilding all ivy/maven packages
 *
 * run in busybox directory
 */
object BusyboxCFGLinker extends App {

    import FeatureExprFactory._

    FeatureExprFactory.setDefault(FeatureExprFactory.bdd)
    val r = new ReduceCFG()

    val filelistFile = new File("filelist")

    if (!filelistFile.exists) {
        System.out.print("start this in the TypeChef-BusyboxAnalysis/gitbusybox directory containing the analysis results")
    } else {

        //        all(filelistFile, true)(getReach)
        val bigCFG = linkSysLibs(composeCFG(filelistFile))
        assert(bigCFG.checkConsistency)
        writeCFG(bigCFG)

//        reduceFileCFGs(filelistFile)
//        allF(filelistFile, true)(writeDots)

    }


    def composeCFG(filelistFile: File): CFG = {

        var bigCFG = new CFG(Set(), Set())

        for (file <- Source.fromFile(filelistFile).getLines()) {
            val cfgFile = file + ".cfg"
            print("linking " + cfgFile)

            val pcFile = new File(file + ".pc")
            val filePC = if (pcFile.exists()) new FeatureExprParser().parseFile(new FileInputStream(pcFile)) else True
            val cfg = new CFGLoader().loadFileCFG(new File(cfgFile), filePC)
            println(".")

            bigCFG = bigCFG link cfg
        }
        assert(bigCFG.checkConsistency)

        println("done.")

        bigCFG
    }


    /**
     * create an artifical module that introduces all declared functions in a syslib.
     * all functiondefinitions have a source file starting with "sys-" ending with
     * stdlib, libc, selinux, or unknown.
     *
     * should only be linked to SysLib after all other modules have been linked. no
     * declarations will remain
     */
    def linkSysLibs(cfg: CFG): CFG = {

        val remainingDeclarations = cfg.nodes.filter(_.kind == "declaration").map(_.name)

        val stdLibFunctions = SystemLinker.stdLibFunctions.filter(remainingDeclarations contains _).toSet
        val libcFunctions = SystemLinker.libcSymbols.filter(remainingDeclarations contains _).toSet
        val seLinuxFunctions = SystemLinker.selinuxLibFunctions.filter(remainingDeclarations contains _).toSet
        val remainingFunctions = remainingDeclarations -- stdLibFunctions -- libcFunctions -- seLinuxFunctions



        val libFunctions =
            stdLibFunctions.map(new CFGNode(IdGen.genId(), "function", new File("sys-stdlib"), -1, _, True)) ++
                libcFunctions.map(new CFGNode(IdGen.genId(), "function", new File("sys-libc"), -1, _, True)) ++
                seLinuxFunctions.map(new CFGNode(IdGen.genId(), "function", new File("sys-selinux"), -1, _, True)) ++
                remainingFunctions.map(new CFGNode(IdGen.genId(), "function", new File("sys-unknown"), -1, _, True))

        val libCFG = new CFG(libFunctions, Set())

        val newCFG = cfg.link(libCFG)

        new CFG(newCFG.nodes.filterNot(_.kind=="declaration"),newCFG.edges)

//        assert(newCFG.nodes.filter(_.kind == "declaration").isEmpty)

    }


    def reduceFileCFGs(filelistFile: File) {
        for (file <- Source.fromFile(filelistFile).getLines()) {

            val cfgFile = file + ".cfg"
            print("reducing " + cfgFile)

            val pcFile = new File(file + ".pc")
            val filePC = if (pcFile.exists()) new FeatureExprParser().parseFile(new FileInputStream(pcFile)) else True
            val cfg = new CFGLoader().loadFileCFG(new File(cfgFile), filePC)
            println(".")
            val newcfg = r.removeSelfCycles(r.reduceMut(cfg, true))

            val writer = new FileWriter(file + ".rcfg")
            newcfg.write(writer)
            writer.close()
        }
    }

    def writeCFG(cfg: CFG) {
        println("writing result")

        val writer = new FileWriter("busybox.cfg")
        cfg.write(writer)
        writer.close()
    }

    def loadCFG(): CFG = {
        println("loading")
        new CFGLoader().loadCFG(new File("busybox.cfg"))
    }

    def testReduceBusybox(cfg: CFG) {
        FeatureExprFactory.setDefault(FeatureExprFactory.bdd)

        println("reducing")
        val newcfg = r.removeSelfCycles(r.reduceMut(cfg))
        println("\nwriting")
        val w = new FileWriter("busybox_reduced.cfg")
        newcfg.write(w)
        w.close
    }

    def all(filelistFile: File, reduced: Boolean = false)(f: CFG => Unit) {
        allF(filelistFile, reduced)((a: String, b: CFG) => f(b))
    }

    def allF(filelistFile: File, reduced: Boolean = false)(f: (String, CFG) => Unit) {
        for (file <- Source.fromFile(filelistFile).getLines())
            f(file, if (reduced) new CFGLoader().loadCFG(new File(file + ".rcfg"))
            else {
                val cfgFile = file + ".cfg"
                val pcFile = new File(file + ".pc")
                val filePC = if (pcFile.exists()) new FeatureExprParser().parseFile(new FileInputStream(pcFile)) else True
                new CFGLoader().loadFileCFG(new File(cfgFile), filePC)
            })
    }

    def getReach(cfg: CFG) {
        val functions = cfg.nodes.filter(_.kind == "function")
        val functionscount = functions.size

        println(functionscount)

        val mainFuns = functions.filter(_.name contains "main").headOption

        mainFuns map {
            mainFun =>
                val reachable = r.getReachableNodes(cfg, mainFun).filter(_.kind == "function")
                println(mainFun.name + ":\t" + reachable.size + "\t" + (100 * reachable.size / functionscount))
        }
    }

    def writeDots(file: String, cfg: CFG) {
        println("writing dot")

        val writer = new FileWriter(file + ".rcfg.dot")
        cfg.writeDot(writer)
        writer.close()
    }
}