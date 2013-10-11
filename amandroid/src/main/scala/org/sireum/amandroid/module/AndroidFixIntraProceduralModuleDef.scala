//package org.sireum.amandroid.module
//
//import org.sireum.util._
//import org.sireum.core.module.PilarParserModuleDef
//import org.sireum.pilar.symbol._
//import org.sireum.pilar.ast._
//import org.sireum.pilar.parser.ChunkingPilarParser
//import org.sireum.alir.ControlFlowGraph
//import org.sireum.alir.AlirIntraProceduralGraph
//import org.sireum.amandroid.android.intraProcedural.reachingDefinitionAnalysis.AndroidReachingDefinitionAnalysis
//import org.sireum.amandroid.intraProcedural.pointsToAnalysis.IntraPointsToAnalysis
//import org.sireum.amandroid.android.interProcedural.objectFlowAnalysis.AndroidOfgPreprocessor
//import org.sireum.amandroid.intraProcedural.compressedControlFlowGraph.CompressedControlFlowGraph
//import org.sireum.amandroid.intraProcedural.pointsToAnalysis.PointerAssignmentGraph
//import org.sireum.amandroid.intraProcedural.pointsToAnalysis.PtaNode
//import org.sireum.amandroid.android.interProcedural.objectFlowAnalysis.AndroidObjectFlowGraph
//import org.sireum.amandroid.interProcedural.objectFlowAnalysis.OfaNode
//import org.sireum.amandroid.android.interProcedural.objectFlowAnalysis.AndroidValueSet
//import org.sireum.pipeline.PipelineJob
//import org.sireum.pipeline.PipelineJobModuleInfo
//import org.sireum.amandroid.symbolResolver.AmandroidSymbolTableBuilder
//import org.sireum.amandroid.symbolResolver.AmandroidSymbolTable
//
//class AndroidFixIntraProceduralModuleDef (val job : PipelineJob, info : PipelineJobModuleInfo) extends AndroidFixIntraProceduralModule {
//  val newProcedures = this.appInfoOpt.get.getDummyMainMap
//  val newModels = newProcedures.map{case (k, v) => ChunkingPilarParser(Left(v), reporter(info)) match{case Some(m) => m; case None => null}}.toList
//  
//  val ms = this.models
//  val par = this.parallel
//  val stp = this.symbolTable.asInstanceOf[SymbolTableProducer]
//  val fst = { _ : Unit => new AmandroidSymbolTable }
//  val result = AmandroidSymbolTableBuilder(stp, ms, Set(), newModels, fst, par)
//
//  val st = result._1.asInstanceOf[AmandroidSymbolTable]
//  info.tags ++= st.tags
//
//  if (st.hasErrors)
//    info.hasError = true
//    
//  this.symbolTable_=(result._1)
//  
//  type VirtualLabel = String
//  val siff = this.shouldIncludeFlowFunction
//  //for building rda
//  val dr = this.defRef
//  val iopp = this.isInputOutputParamPredicate
//  val saom = this.switchAsOrderedMatch
//  val newPUris = result._3
//  var tempResult = 
//    this.intraResult ++
//    newPUris.map{
//	    pUri=>
//	      val pst = result._1.procedureSymbolTable(pUri)
//	      val (pool, cfg) = buildCfg(pst)
//		    var rdaOpt : Option[AndroidReachingDefinitionAnalysis.Result] = None
//		    var pagOpt : Option[PointerAssignmentGraph[PtaNode]] = None
//		    var ofgOpt : Option[AndroidObjectFlowGraph[OfaNode, AndroidValueSet]] = None
//		    var cCfgOpt : Option[CompressedControlFlowGraph[VirtualLabel]] = None
//		
//		    if (this.shouldBuildRda){
//		      val rda = buildRda(pst, cfg)
//		      rdaOpt = Some(rda)
//		      if(this.shouldBuildPag){
//		        val pag = buildPag(pst, cfg, rda)
//		        pagOpt = Some(pag)
//		      }
//		      if(this.shouldPreprocessOfg){
//		        val ofg = preprocessOFA(pst, cfg, rda)
//		        ofgOpt = Some(ofg)
//		      }
//		      if (this.shouldBuildCCfg){
//		        val cCfg = buildCCfg(pst, cfg, pool)
//		        cCfgOpt = Some(cCfg)
//		      }
//		    }
//		    
//		    Map(pst.procedureUri ->
//		      AndroidIntraProcedural.AndroidIntraAnalysisResult(
//		        pool, cfg, rdaOpt, pagOpt, ofgOpt, cCfgOpt
//		      ))
//	  }.reduce(combine)
//  
//	this.intraResult_=(tempResult)
//	  
//  def combine(tp1 : IMap[ResourceUri, AndroidIntraProcedural.AndroidIntraAnalysisResult],
//      tp2 : IMap[ResourceUri, AndroidIntraProcedural.AndroidIntraAnalysisResult])
//      : IMap[ResourceUri, AndroidIntraProcedural.AndroidIntraAnalysisResult] = {
//    tp1 ++ tp2
//  }
//
//  def buildCfg(pst : ProcedureSymbolTable) = {
//	  val ENTRY_NODE_LABEL = "Entry"
//	  val EXIT_NODE_LABEL = "Exit"
//	  val pool : AlirIntraProceduralGraph.NodePool = mmapEmpty
//	  val result = ControlFlowGraph[VirtualLabel](pst, ENTRY_NODE_LABEL, EXIT_NODE_LABEL, pool, siff)
//	  (pool, result)
//	}
//	
//	def buildRda (pst : ProcedureSymbolTable, cfg : ControlFlowGraph[VirtualLabel]) = {
//	  val iiopp = iopp(pst)
//	  AndroidReachingDefinitionAnalysis[VirtualLabel](pst,
//	    cfg,
//	    defRef(pst.symbolTable),
//	    first2(iiopp),
//	    saom)
//	}
//	
//	def buildPag (pst : ProcedureSymbolTable, cfg : ControlFlowGraph[VirtualLabel], rda : AndroidReachingDefinitionAnalysis.Result) = {
//	  new IntraPointsToAnalysis().build(pst, cfg, rda)
//	}
//	
//	def preprocessOFA (pst : ProcedureSymbolTable, cfg : ControlFlowGraph[VirtualLabel], rda : AndroidReachingDefinitionAnalysis.Result) = {
//	  AndroidOfgPreprocessor(pst, cfg, rda)
//	}
//	
//	def buildCCfg (pst : ProcedureSymbolTable, cfg : ControlFlowGraph[VirtualLabel], pool : AlirIntraProceduralGraph.NodePool) = {
//	  CompressedControlFlowGraph[String](pst, pool, cfg)
//	}
//  
//  def reporter(info : PipelineJobModuleInfo) =
//    new org.sireum.pilar.parser.PilarParser.ErrorReporter {
//      def report(source : Option[FileResourceUri], line : Int,
//                 column : Int, message : String) =
//        info.tags += Tag.toTag(source, line, column, message, PilarParserModuleDef.ERROR_TAG_TYPE)
//    }
//}