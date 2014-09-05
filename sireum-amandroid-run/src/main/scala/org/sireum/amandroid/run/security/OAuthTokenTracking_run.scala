package org.sireum.amandroid.run.security

import org.sireum.amandroid.security.AmandroidSocket
import org.sireum.amandroid.security.AmandroidSocketListener
import org.sireum.amandroid.alir.AndroidGlobalConfig
import java.io.PrintWriter
import java.io.File
import org.sireum.amandroid.security.oauth.OauthTokenContainerCollector
import org.sireum.amandroid.alir.dataRecorder.DataCollector
import org.sireum.amandroid.alir.dataRecorder.MetricRepo
import org.sireum.jawa.MessageCenter
import org.sireum.jawa.MessageCenter._
import org.sireum.amandroid.alir.reachingFactsAnalysis.AndroidReachingFactsAnalysisConfig
import org.sireum.jawa.util.Timer
import org.sireum.util._
import org.sireum.amandroid.security.oauth.OAuthSourceAndSinkManager
import org.sireum.amandroid.android.util.AndroidLibraryAPISummary
import org.sireum.amandroid.alir.AppCenter
import org.sireum.jawa.util.IgnoreException


/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 */
object OAuthTokenTracking_run {
  private final val TITLE = "OAuthTokenTracking_run"
  object OAuthTokenCounter {
    var total = 0
    var haveresult = 0
    var foundOauthContainer = 0
    var taintPathFound = 0
    var foundOauthContainerList = Set[String]()
    var taintPathFoundList = Set[String]()
    override def toString : String = "total: " + total + ", haveResult: " + haveresult + ", foundOauthContainer: " + foundOauthContainer + ", taintPathFound: " + taintPathFound
    
    val appRec = mmapEmpty[String, Int]
    def addRecs(names : Iterable[String]) = {
      names.foreach{
        n =>
          if(appRec.contains(n)){
            appRec(n) = appRec(n) + 1
          }
          else appRec(n) = 1
      }
    }
    
    def outputRecStatistic = {
    	val outputDir = AndroidGlobalConfig.amandroid_home + "/output"
      val appDataDirFile = new File(outputDir + "/recStatistic")
    	if(!appDataDirFile.exists()) appDataDirFile.mkdirs()
    	val out = new PrintWriter(appDataDirFile + "/RecStatistic.txt")
      appRec.filter(p=> p._2 >= 5).toSeq.sortBy(_._1).sortBy(_._2).foreach(out.println(_))
      out.close()
    }
    
    def outputInterestingFileNames = {
    	val outputDir = AndroidGlobalConfig.amandroid_home + "/output"
    	val appDataDirFile = new File(outputDir + "/interestingApps")
    	if(!appDataDirFile.exists()) appDataDirFile.mkdirs()
    	val out = new PrintWriter(appDataDirFile + "/interestingApps.txt")
      out.println("\n\n\n\nfoundPasswordContainerList:")
      foundOauthContainerList.foreach(out.println(_))
      out.println("\n\n\n\ntaintPathFoundList:")
      taintPathFoundList.foreach(out.println(_))
      out.close()
    }
  }
  
  private class OAuthTokenTrackingListener(source_apk : FileResourceUri, app_info : OauthTokenContainerCollector) extends AmandroidSocketListener {
    def onPreAnalysis: Unit = {
      OAuthTokenCounter.total += 1
    }

    def entryPointFilter(eps: Set[org.sireum.jawa.JawaProcedure]): Set[org.sireum.jawa.JawaProcedure] = {
      val iacs = app_info.getInterestingContainers(Set("access_token"))
    	val res = eps.filter(e=>iacs.contains(e.getDeclaringRecord))
    	if(!res.isEmpty){
    	  OAuthTokenCounter.foundOauthContainer += 1
    	  OAuthTokenCounter.foundOauthContainerList += source_apk
    	}
      res
    }

    def onTimeout : Unit = {}

    def onAnalysisSuccess : Unit = {
      if(AppCenter.getTaintAnalysisResults.exists(!_._2.getTaintedPaths.isEmpty)){
	      OAuthTokenCounter.taintPathFound += 1
	      OAuthTokenCounter.taintPathFoundList += source_apk
	    }
      val appData = DataCollector.collect
    	MetricRepo.collect(appData)
    	val outputDir = AndroidGlobalConfig.amandroid_home + "/output"
    	val apkName = source_apk.substring(source_apk.lastIndexOf("/"), source_apk.lastIndexOf("."))
    	val appDataDirFile = new File(outputDir + "/" + apkName)
    	if(!appDataDirFile.exists()) appDataDirFile.mkdirs()
    	val out = new PrintWriter(appDataDirFile + "/AppData.txt")
	    out.print(appData.toString)
	    out.close()
	    val mr = new PrintWriter(outputDir + "/MetricInfo.txt")
		  mr.print(MetricRepo.toString)
		  mr.close()
		  OAuthTokenCounter.haveresult += 1
    }

    def onPostAnalysis: Unit = {
      msg_critical(TITLE, OAuthTokenCounter.toString)
    }
    
    def onException(e : Exception) : Unit = {
      e match{
        case ie : IgnoreException => System.err.println("Ignored!")
        case a => 
          e.printStackTrace()
      }
    }
  }
  
  def main(args: Array[String]): Unit = {
    if(args.size != 2){
      System.err.print("Usage: source_path output_path")
      return
    }
    
    MessageCenter.msglevel = MessageCenter.MSG_LEVEL.CRITICAL
    
    AndroidReachingFactsAnalysisConfig.k_context = 1
    AndroidReachingFactsAnalysisConfig.resolve_icc = false
    AndroidReachingFactsAnalysisConfig.resolve_static_init = false
    AndroidReachingFactsAnalysisConfig.timerOpt = Some(new Timer(10))
    
    val socket = new AmandroidSocket
    socket.preProcess
    
    val sourcePath = args(0)
    val outputPath = args(1)
    
    val files = FileUtil.listFiles(FileUtil.toUri(sourcePath), ".apk", true).toSet
    
    files.foreach{
      file =>
        msg_critical(TITLE, "####" + file + "#####")
        val app_info = new OauthTokenContainerCollector(file)
        socket.loadApk(file, outputPath, AndroidLibraryAPISummary, app_info)
        val ssm = new OAuthSourceAndSinkManager(app_info.getPackageName, app_info.getLayoutControls, app_info.getCallbackMethods, AndroidGlobalConfig.SourceAndSinkFilePath)
        socket.plugWithDDA(ssm, false, true, Some(new OAuthTokenTrackingListener(file, app_info)))
    }
  }
}

