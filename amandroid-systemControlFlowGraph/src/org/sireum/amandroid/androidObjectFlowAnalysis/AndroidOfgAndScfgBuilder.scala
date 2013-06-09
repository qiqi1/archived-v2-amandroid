package org.sireum.amandroid.androidObjectFlowAnalysis

import org.sireum.alir.AlirEdge
import org.sireum.alir.ControlFlowGraph
import org.sireum.util._
import org.sireum.pilar.symbol.ProcedureSymbolTable
import org.sireum.pilar.ast._
import org.sireum.alir.ReachingDefinitionAnalysis
import org.sireum.amandroid.AndroidSymbolResolver.AndroidLibInfoTables
import org.sireum.amandroid.cache.AndroidCacheFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import scala.collection.JavaConversions._
import org.sireum.amandroid.util.SignatureParser
import org.sireum.amandroid.scfg.CompressedControlFlowGraph
import org.sireum.amandroid.scfg.SystemControlFlowGraph
import org.sireum.amandroid.scfg.sCfg
import org.sireum.amandroid.objectFlowAnalysis._


class AndroidOfgAndScfgBuilder[Node <: OfaNode, VirtualLabel] {

  type Edge = AlirEdge[Node]
  
  var appInfo : PrepareApp = null
  val pstMap : MMap[ResourceUri, ProcedureSymbolTable] = mmapEmpty
  var processed : MMap[ResourceUri, PointProc] = null
  var cfgs : MMap[ResourceUri, ControlFlowGraph[String]] = null
  var rdas : MMap[ResourceUri, ReachingDefinitionAnalysis.Result] = null
  var cCfgs : MMap[ResourceUri, CompressedControlFlowGraph[String]] = null
  var androidLibInfoTables : AndroidLibInfoTables = null
  var androidCache : AndroidCacheFile[String] = null
  //a map from return node to its possible updated value set
  var stringValueSetMap : MMap[OfaNode, MMap[ResourceUri, ResourceUri]] = mmapEmpty
  var nativeValueSetMap : MMap[OfaNode, MMap[ResourceUri, ResourceUri]] = mmapEmpty
  
  def apply(psts : Seq[ProcedureSymbolTable],
            cfgs : MMap[ResourceUri, ControlFlowGraph[String]],
            rdas : MMap[ResourceUri, ReachingDefinitionAnalysis.Result],
            cCfgs : MMap[ResourceUri, CompressedControlFlowGraph[String]],
            androidLibInfoTables : AndroidLibInfoTables,
            appInfo : PrepareApp,
            androidCache : AndroidCacheFile[String]) 
            = build(psts, cfgs, rdas, cCfgs, androidLibInfoTables, appInfo, androidCache)

  def build(psts : Seq[ProcedureSymbolTable],
            cfgs : MMap[ResourceUri, ControlFlowGraph[String]],
            rdas : MMap[ResourceUri, ReachingDefinitionAnalysis.Result],
            cCfgs : MMap[ResourceUri, CompressedControlFlowGraph[String]],
            androidLibInfoTables : AndroidLibInfoTables,
            appInfo : PrepareApp,
            androidCache : AndroidCacheFile[String])
   : (AndroidObjectFlowGraph[Node], SystemControlFlowGraph[String]) = {
    var result : (AndroidObjectFlowGraph[Node], SystemControlFlowGraph[String]) = null
    this.appInfo = appInfo
    this.cfgs = cfgs
    this.rdas = rdas
    this.cCfgs = cCfgs
    this.androidLibInfoTables = androidLibInfoTables
    this.androidCache = androidCache
    psts.foreach(
      pst =>{
        pstMap(pst.procedureUri) = pst
      }  
    )
//    var entryPoint : ResourceUri = getEntryPoint(psts)
//    if(entryPoint == null){
//      System.err.println("Cannot find the entry point of the app.")
//      return null
//    }
    val ofg = new AndroidObjectFlowGraph[Node]
    ofg.setIntentDB(appInfo.getIntentDB)
    ofg.setEntryPoints(appInfo.getEntryPoints)
    val sCfg = new sCfg[String]
    processed = mmapEmpty
    appInfo.getDummyMainSigMap.values.foreach{
      dummySig =>
        val dummyUri = androidLibInfoTables.getProcedureUriBySignature(dummySig)
        val cfg = cfgs(dummyUri)
		    val rda = rdas(dummyUri)
		    val cCfg = cCfgs(dummyUri)
		    val pst = pstMap(dummyUri)
		    doOFA(pst, cfg, rda, cCfg, ofg, sCfg)
    }
    overallFix(ofg, sCfg)
    result = (ofg, sCfg)
//    result._1.nodes.foreach(
//      node => {
//        val name = node.toString()
//        val valueSet = node.getProperty(result._1.VALUE_SET).asInstanceOf[MMap[ResourceUri, ResourceUri]] filter {case (k, v) => v.equals("STRING")}
//        if(!valueSet.isEmpty)
//        	println("node:" + name + "\nvalueSet:" + valueSet)
//      }
//    )
    println("processed--->" + processed.size)
//    println("arrayrepo------>" + result._1.arrayRepo)
//    println("globalrepo------>" + result._1.globalDefRepo)
//    println("fieldrepo----->" + result._1.iFieldDefRepo)
    val f = new File(System.getProperty("user.home") + "/Desktop/ofg.dot")
    val o = new FileOutputStream(f)
    val w = new OutputStreamWriter(o)
    result._1.toDot(w)
    val f1 = new File(System.getProperty("user.home") + "/Desktop/sCfg.dot")
    val o1 = new FileOutputStream(f1)
    val w1 = new OutputStreamWriter(o1)
    result._2.toDot(w1)
    pstMap.clear
    result
  }
  
  def getEntryPoint(psts : Seq[ProcedureSymbolTable]) : ResourceUri = {
    var entryPoint : ResourceUri = null
    val entryName = this.appInfo.getMainEntryName
    psts.foreach(
      pst =>{
        if(pst.procedureUri.contains(entryName)) entryPoint = pst.procedureUri
      }  
    )
    entryPoint
  }
  
  def doOFA(pst : ProcedureSymbolTable,
            cfg : ControlFlowGraph[String],
            rda : ReachingDefinitionAnalysis.Result,
            cCfg : CompressedControlFlowGraph[String],
            ofg : AndroidObjectFlowGraph[Node],
            sCfg : SystemControlFlowGraph[String]) : Unit = {
    val points = new PointsCollector[Node]().points(pst, ofg)
    ofg.points ++= points
    setProcessed(points, pst.procedureUri)
    ofg.constructGraph(points, cfg, rda)
    sCfg.collectionCCfgToBaseGraph(pst.procedureUri, cCfg)
    fix(ofg, sCfg)
  }
  
  def overallFix(ofg : AndroidObjectFlowGraph[Node],
		  					 sCfg : SystemControlFlowGraph[String]) : Unit = {
    while(checkAndDoIccOperation(ofg, sCfg)){
    	fix(ofg, sCfg)
    }
  }
  
  def fix(ofg : AndroidObjectFlowGraph[Node],
		  		sCfg : SystemControlFlowGraph[String]) : Unit = {
    while (!ofg.worklist.isEmpty) {
      //for construct and extend graph for static method invocation 
      while(!ofg.staticMethodList.isEmpty) {
        val pi = ofg.staticMethodList.remove(0)
        val callee = ofg.getDirectCallee(pi, androidLibInfoTables)
        extendGraphWithConstructGraph(callee, pi, ofg, sCfg)
      }
      //end
      val n = ofg.worklist.remove(0)
      ofg.successors(n).foreach(
        succ => {
          n match {
            case ofn : OfaFieldNode =>
                ofg.updateFieldValueSet(ofn)
            case _ =>
          }
          val vsN = n.propertyMap(ofg.VALUE_SET).asInstanceOf[MMap[ResourceUri, ResourceUri]]
          val vsSucc = succ.propertyMap(ofg.VALUE_SET).asInstanceOf[MMap[ResourceUri, ResourceUri]]
          val d = getDiff(vsN, vsSucc)
          if(!d.isEmpty){
            vsSucc ++= d
            ofg.worklist += succ
            //check whether it's a global variable node, if yes, then populate globalDefRepo
            //check whether it's a base node of field access, if yes, then populate/use iFieldDefRepo.
            succ match {
              case ogvn : OfaGlobalVarNode =>
                ofg.populateGlobalDefRepo(d, ogvn)
              case ofbn : OfaFieldBaseNode =>
                val fieldNode = ofbn.fieldNode
                ofg.updateFieldValueSet(d, fieldNode)
              case ofn : OfaFieldNode =>
                ofg.populateIFieldRepo(d, ofn)
              case _ =>
            }
            //ends here
            val piOpt = ofg.recvInverse(succ)
            piOpt match {
              case Some(pi) =>
                val calleeSet : MSet[ResourceUri] = msetEmpty
                if(pi.typ.equals("direct") || pi.typ.equals("super")){
                  calleeSet += ofg.getDirectCallee(pi, androidLibInfoTables)
                } else {
                  calleeSet ++= ofg.getCalleeSet(d, pi, androidLibInfoTables)
                }
//                if(pi.toString.contains("[|Lcom/fgweihlp/wfgnp/MainActivity;.sendBroadcast:(Landroid/content/Intent;)V|]"))
//                	println("pi = " + (pi, d, calleeSet))
                calleeSet.foreach(
                  callee => {
                    extendGraphWithConstructGraph(callee, pi, ofg, sCfg)
                  }  
                )
              case None =>
            }
          }
        }  
      )
      //do special operation
      doSpecialOperation(ofg, "STRING")
      doSpecialOperation(ofg, "NATIVE")
    }
  }
  
  def checkAndDoIccOperation(ofg : AndroidObjectFlowGraph[Node], sCfg : SystemControlFlowGraph[String]) : Boolean = {
    var flag = true
    val result = ofg.doIccOperation(this.appInfo.getDummyMainSigMap)
    if(result != null){
      val (pi, targetSigs) = result
	    targetSigs.foreach{
	      targetSig =>
	        val targetUri = androidLibInfoTables.getProcedureUriBySignature(targetSig)
	        if(targetUri != null){
	          if(processed.contains(targetUri)){
			        val procPoint = processed(targetUri)
				      require(procPoint != null)
				      ofg.extendGraphForIcc(procPoint, pi)
				      sCfg.extendGraph(targetUri, pi.owner, pi.locationUri, pi.locationIndex)
	          }
	        }
	    }
    } else flag = false
    flag
  }
  
  def doSpecialOperation(ofg : ObjectFlowGraph[Node], typ : String) = {
    typ match{
      case "STRING" =>
	      val vsMap = ofg.doStringOperation
	      vsMap.map{
	        case (k, v) =>
	          if(stringValueSetMap.contains(k)){
	          	val d = getDiff(v, stringValueSetMap(k))
	          	if(!d.isEmpty){
		          	k.getProperty[MMap[ResourceUri, ResourceUri]](ofg.VALUE_SET) ++= d
		          	ofg.worklist += k.asInstanceOf[Node]
	          	}
	          } else {
	            k.getProperty[MMap[ResourceUri, ResourceUri]](ofg.VALUE_SET) ++= v
	          	ofg.worklist += k.asInstanceOf[Node]
	          }
	      }
	      stringValueSetMap = vsMap
      case "NATIVE" =>
	      val vsMap = ofg.doNativeOperation
	      vsMap.map{
	        case (k, v) =>
	          if(nativeValueSetMap.contains(k)){
	          	val d = getDiff(v, nativeValueSetMap(k))
	          	if(!d.isEmpty){
		          	k.getProperty[MMap[ResourceUri, ResourceUri]](ofg.VALUE_SET) ++= d
		          	ofg.worklist += k.asInstanceOf[Node]
	          	}
	          } else {
	            k.getProperty[MMap[ResourceUri, ResourceUri]](ofg.VALUE_SET) ++= v
	          	ofg.worklist += k.asInstanceOf[Node]
	          }
	      }
	      nativeValueSetMap = vsMap
    }
  }
  
  def getDiff(map1 : MMap[ResourceUri, ResourceUri], map2 : MMap[ResourceUri, ResourceUri]) = {
    val d = mmapEmpty[ResourceUri, ResourceUri]
    map1.keys.map{ case k => if(map2.contains(k)){if(!map1(k).equals(map2(k))){d(k) = map1(k)}}else{d(k) = map1(k)} }
    d
  }
  
  // callee is signature
  def extendGraphWithConstructGraph(callee : ResourceUri, 
      															pi : PointI, 
      															ofg : AndroidObjectFlowGraph[Node], 
      															sCfg : SystemControlFlowGraph[String]) = {
    val points : MList[Point] = mlistEmpty
    val calleeSig : ResourceUri = androidLibInfoTables.getProcedureSignatureByUri(callee)
    if(ofg.isStringOperation(calleeSig)){
      if(new SignatureParser(calleeSig).getParamSig.isReturnNonNomal){
        val calleeOfg = androidCache.load[ObjectFlowGraph[Node]](callee, "ofg")
        processed(callee) = ofg.combineSpecialOfg(calleeSig, calleeOfg, "STRING")
      }
    } else if(ofg.isNativeOperation(androidLibInfoTables.getAccessFlag(callee))) {
      if(new SignatureParser(calleeSig).getParamSig.isReturnNonNomal){
        val calleeOfg = androidCache.load[ObjectFlowGraph[Node]](callee, "ofg")
        processed(callee) = ofg.combineSpecialOfg(calleeSig, calleeOfg, "NATIVE")
      }
    } else if(ofg.isIccOperation(calleeSig, androidLibInfoTables)) {
      ofg.setIccOperationTracker(calleeSig, pi)
    } else if(!processed.contains(callee)){
      if(pstMap.contains(callee)){
        val cfg = cfgs(callee)
        val rda = rdas(callee)
        val cCfg = cCfgs(callee)
        if(callee.contains("onCreate"))
          println("callee =" + callee)
        points ++= new PointsCollector[Node]().points(pstMap(callee), ofg)
        ofg.points ++= points
        setProcessed(points, callee)
        ofg.constructGraph(points, cfg, rda)
        sCfg.collectionCCfgToBaseGraph(callee, cCfg)
      } else {
        //get ofg ccfg from file
        val calleeOfg = androidCache.load[ObjectFlowGraph[Node]](callee, "ofg")
        val calleeCCfg = androidCache.load[CompressedControlFlowGraph[String]](callee, "cCfg")
        processed(callee) = ofg.combineOfgs(calleeOfg)
        sCfg.collectionCCfgToBaseGraph(callee, calleeCCfg)
      }
    }
    if(processed.contains(callee)){
      val procPoint = processed(callee)
      if(callee.contains("onCreate"))
          println("processed callee =" + callee)
      require(procPoint != null)
      ofg.extendGraph(procPoint, pi)
      if(!ofg.isStringOperation(calleeSig) && 
         !ofg.isNativeOperation(androidLibInfoTables.getAccessFlag(callee)) &&
         !ofg.isIccOperation(calleeSig, androidLibInfoTables))
      	sCfg.extendGraph(callee, pi.owner, pi.locationUri, pi.locationIndex)
//      else println("pi--->" + pi)
    } else {
      //need to extend
    }
  }
  
  def setProcessed(points : MList[Point], callee : ResourceUri) = {
    points.foreach(
      point => {
        if(point.isInstanceOf[PointProc]){
          processed(callee) = point.asInstanceOf[PointProc]
        }
      }
    )
  }
}