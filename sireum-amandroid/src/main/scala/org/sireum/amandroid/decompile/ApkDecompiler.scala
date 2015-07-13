package org.siruem.amandroid.decompile

import java.io.File
import org.sireum.util._
import org.sireum.amandroid.decompile.Dex2PilarConverter
import org.sireum.util.FileResourceUri
import org.sireum.jawa.sjc.util.MyFileUtil
import org.sireum.jawa.sjc.refactoring.RefactorJawa
import java.io.FileWriter
import org.sireum.amandroid.parser.ManifestParser
import org.sireum.amandroid.AndroidConstants
import org.sireum.jawa.io.FgSourceFile
import org.sireum.jawa.io.PlainFile
import org.sireum.amandroid.decompile.AmDecoder

object ApkDecompiler {
  def decompile(apk: File, projectLocation: File, removeSupportGen: Boolean): (FileResourceUri, ISet[String]) = {
    val out = AmDecoder.decode(FileUtil.toUri(apk), FileUtil.toUri(projectLocation), false)
    val dexFile = out + "/classes.dex"
    if(FileUtil.toFile(out).exists()) {
      val src = Dex2PilarConverter.convert(dexFile, out + "/src")
      if(removeSupportGen) return (out, removeSupportLibAndGen(src, ManifestParser.loadPackageName(apk)))
    }
    (out, isetEmpty)
  }
  
  def removeSupportLibAndGen(src: FileResourceUri, pkg: String): ISet[String] = {
    val dependencies: MSet[String] = msetEmpty
    val pkgPath = pkg.replaceAll("\\.", "/")
    val srcDir = FileUtil.toFile(src)
    val worklist: MList[File] = mlistEmpty
    MyFileUtil.listFilesAndDir(srcDir) foreach {
      f =>
        if(f.isDirectory()){
          if(f.getAbsolutePath.endsWith("/android/support/v4")){
            worklist += f
            dependencies += AndroidConstants.MAVEN_SUPPORT_V4
          } else if (f.getAbsolutePath.endsWith("/android/support/v13")) {
            worklist += f
            dependencies += AndroidConstants.MAVEN_SUPPORT_V13
          } else if (f.getAbsolutePath.endsWith("/android/support/v7/appcompat")){
            worklist += f
            dependencies += AndroidConstants.MAVEN_APPCOMPAT
          }
        }
        if(f.getAbsolutePath.contains("/" + pkgPath + "/BuildConfig.pilar") ||
           f.getAbsolutePath.contains("/" + pkgPath + "/Manifest.pilar") ||
           f.getAbsolutePath.contains("/" + pkgPath + "/Manifest$") ||
           f.getAbsolutePath.contains("/" + pkgPath + "/R.pilar") ||
           f.getAbsolutePath.contains("/" + pkgPath + "/R$")) {
          if(!f.isDirectory()) worklist += f
        }
    }
    while(!worklist.isEmpty){
      val f = worklist.remove(0)
      MyFileUtil.deleteDir(f)
    }
    MyFileUtil.clearDirIfNoFile(srcDir)
    
    /**
     * refactor phase
     */
    FileUtil.listFiles(src, "pilar", true) foreach {
      f =>
        val code = new FgSourceFile(new PlainFile(FileUtil.toFile(f))).code
        val newcode = RefactorJawa(code)
        val file = FileUtil.toFile(f)
        val fw = new FileWriter(file, false)
        fw.write(newcode)
        fw.close()
    }
    dependencies.toSet
  }
}