/*******************************************************************************
 * Copyright (c) 2013 - 2016 Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Detailed contributors are listed in the CONTRIBUTOR.md
 ******************************************************************************/
package org.sireum.amandroid.security

import org.sireum.util.FileUtil
import org.sireum.amandroid.AndroidGlobalConfig
import org.sireum.jawa.alir.LibSideEffectProvider
import java.io.File
import org.sireum.amandroid.decompile.Dex2PilarConverter
import org.sireum.amandroid.alir.pta.reachingFactsAnalysis.AndroidRFAConfig
import org.sireum.jawa.LibraryAPISummary
import org.sireum.amandroid.appInfo.AppInfoCollector
import org.sireum.amandroid.AndroidConstants
import org.sireum.amandroid.alir.pta.reachingFactsAnalysis.AndroidReachingFactsAnalysis
import org.sireum.jawa.ClassLoadManager
import org.sireum.jawa.alir.dataDependenceAnalysis.InterproceduralDataDependenceAnalysis
import org.sireum.amandroid.alir.taintAnalysis.AndroidDataDependentTaintAnalysis
import org.sireum.jawa.util.IgnoreException
import org.sireum.amandroid.alir.taintAnalysis.AndroidSourceAndSinkManager
import org.sireum.jawa.JawaMethod
import org.sireum.util.FileResourceUri
import org.sireum.jawa.alir.Context
import org.sireum.jawa.ScopeManager
import org.sireum.amandroid.alir.pta.reachingFactsAnalysis.AndroidRFAScopeManager
import org.sireum.jawa.Global
import org.sireum.jawa.Constants
import org.sireum.amandroid.Apk
import org.sireum.amandroid.decompile.ApkDecompiler
import org.sireum.amandroid.alir.taintAnalysis.AndroidDataDependentTaintAnalysis.TarApk
import org.sireum.amandroid.alir.componentSummary.ApkYard
import java.util.concurrent.TimeoutException
import org.sireum.jawa.alir.pta.reachingFactsAnalysis.RFAFactFactory
import org.sireum.jawa.util.MyTimeout

/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 * @deprecated Use org.sireum.amandroid.alir.componentSummary.ComponentBasedAnalysis instead.
 */ 
@deprecated
trait AmandroidSocketListener {
  def onPreAnalysis: Unit
  def entryPointFilter(eps: Set[JawaMethod]): Set[JawaMethod]
  def onAnalysisSuccess: Unit
  def onException(e: Exception): Unit
  def onPostAnalysis: Unit
}

/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 */
@deprecated
class AmandroidSocket(global: Global, yard: ApkYard, apk: Apk) {
  private final val TITLE = "AmandroidSocket"
  private var myListener_opt: Option[AmandroidSocketListener] = None
//  private var dirtyFlag = false
  
//  def preProcess: Unit = {
//    if(dirtyFlag) throw new RuntimeException("Before your analysis please call cleanEnv first.")
//    dirtyFlag = true
////    val imgfile = new File(AndroidGlobalConfig.android_libsummary_dir + "/AndroidLibSummary.xml.zip")
//    JawaCodeSource.preLoad(FileUtil.toUri(AndroidGlobalConfig.android_lib_dir), GlobalConfig.PILAR_FILE_EXT)
////    val libsum_file = new File(AndroidGlobalConfig.android_libsummary_dir + "/AndroidLibSideEffectResult.xml.zip")
////    if(libsum_file.exists())
////      LibSideEffectProvider.init(libsum_file)
//  }
  
  def plugListener(listener: AmandroidSocketListener): Unit = {
    myListener_opt = Some(listener)
  }
  
  def loadApk(output_path: String, lib_sum: LibraryAPISummary, dpsuri: Option[FileResourceUri], dexLog: Boolean, debugMode: Boolean, forceDelete: Boolean = true): FileResourceUri = {
    val apkFile = FileUtil.toFile(apk.nameUri)
//    val name = try{apkFile.getName.substring(0, apkFile.getName().lastIndexOf(".apk"))} catch {case e: Exception => apkFile.getName}
    val resultDir = new File(output_path)
    val (outUri, srcs, _) = ApkDecompiler.decompile(apkFile, resultDir, dpsuri, dexLog, debugMode, true, forceDelete)
    // convert the dex file to the "pilar" form
    srcs foreach {
      src =>
        val fileUri = FileUtil.toUri(FileUtil.toFilePath(outUri) + File.separator + src)
        if(FileUtil.toFile(fileUri).exists()) {
          //store the app's pilar code in AmandroidCodeSource which is organized class by class.
          global.load(fileUri, Constants.PILAR_FILE_EXT, lib_sum)
        }
    }
    outUri
  }
  
  /**
   * Always call this after analysis one application.
   */
  def cleanEnv = {
//    dirtyFlag = false
//    Center.reset
//    AppCenter.reset
    // before starting the analysis of the current app, first clear the previous app's records' code from the AmandroidCodeSource
//    JawaCodeSource.clearAppClassCodes
    System.gc()
    System.gc()
  }
  
  def runWithDDA(
      ssm: AndroidSourceAndSinkManager,
      public_only: Boolean,
      parallel: Boolean) = {    
    try {
      if(myListener_opt.isDefined) myListener_opt.get.onPreAnalysis
  
      var entryPoints = global.getEntryPoints(AndroidConstants.MAINCOMP_ENV)
  
      if(!public_only)
        entryPoints ++= global.getEntryPoints(AndroidConstants.COMP_ENV)
        
      if(myListener_opt.isDefined) 
        entryPoints = myListener_opt.get.entryPointFilter(entryPoints)
    
      ScopeManager.setScopeManager(new AndroidRFAScopeManager)
        
      val tarApk = new TarApk
      
      {if(parallel) entryPoints.par else entryPoints}.foreach {
        ep =>
          global.reporter.echo(TITLE, "--------------Component " + ep + "--------------")
          implicit val factory = new RFAFactFactory
          val initialfacts = AndroidRFAConfig.getInitialFactsForMainEnvironment(ep)
          val idfg = AndroidReachingFactsAnalysis(global, apk, ep, initialfacts, new ClassLoadManager, timeout = None)
          yard.addIDFG(ep.getDeclaringClass.getType, idfg)
          global.reporter.echo(TITLE, "processed-->" + idfg.icfg.getProcessed.size)
          val iddResult = InterproceduralDataDependenceAnalysis(global, idfg)
          yard.addIDDG(ep.getDeclaringClass.getType, iddResult)
          val tar = AndroidDataDependentTaintAnalysis(global, iddResult, idfg.ptaresult, ssm)
          tarApk.tars += tar
      }
      yard.addTaintAnalysisResult(apk.nameUri, tarApk)
      if(myListener_opt.isDefined) myListener_opt.get.onAnalysisSuccess
    } catch {
      case e: Exception => 
        if(myListener_opt.isDefined) myListener_opt.get.onException(e)
    } finally {
      if(myListener_opt.isDefined) myListener_opt.get.onPostAnalysis
    }
  }
  
  def runWithoutDDA(
      public_only: Boolean,
      parallel: Boolean) = {    
    try{
      if(myListener_opt.isDefined) myListener_opt.get.onPreAnalysis
  
      // before starting the analysis of the current app, first reset the Center which may still hold info (of the resolved records) from the previous analysis

      var entryPoints = global.getEntryPoints(AndroidConstants.MAINCOMP_ENV)
      
      if(!public_only)
        entryPoints ++= global.getEntryPoints(AndroidConstants.COMP_ENV)
    
      if(myListener_opt.isDefined) 
        entryPoints = myListener_opt.get.entryPointFilter(entryPoints)
  
      ScopeManager.setScopeManager(new AndroidRFAScopeManager)
        
      {if(parallel) entryPoints.par else entryPoints}.foreach{
        ep =>
          try {
            global.reporter.echo(TITLE, "--------------Component " + ep + "--------------")
            implicit val factory = new RFAFactFactory
            val initialfacts = AndroidRFAConfig.getInitialFactsForMainEnvironment(ep)
            val idfg = AndroidReachingFactsAnalysis(global, apk, ep, initialfacts, new ClassLoadManager, timeout = None)
            yard.addIDFG(ep.getDeclaringClass.getType, idfg)
            global.reporter.echo(TITLE, "processed-->" + idfg.icfg.getProcessed.size)
            val iddResult = InterproceduralDataDependenceAnalysis(global, idfg)
            yard.addIDDG(ep.getDeclaringClass.getType, iddResult)
          } catch {
            case te: TimeoutException => global.reporter.error(TITLE, ep + ":" + te.getMessage)
          }
      }
      if(myListener_opt.isDefined) myListener_opt.get.onAnalysisSuccess
    } catch {
      case e: Exception => 
        if(myListener_opt.isDefined) myListener_opt.get.onException(e)
    } finally {
      if(myListener_opt.isDefined) myListener_opt.get.onPostAnalysis
    }
  }
}
