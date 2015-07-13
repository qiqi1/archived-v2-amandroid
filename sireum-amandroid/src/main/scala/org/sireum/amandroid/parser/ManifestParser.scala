/*
Copyright (c) 2013-2014 Fengguo Wei & Sankardas Roy, Kansas State University.        
All rights reserved. This program and the accompanying materials      
are made available under the terms of the Eclipse Public License v1.0 
which accompanies this distribution, and is available at              
http://www.eclipse.org/legal/epl-v10.html                             
*/
package org.sireum.amandroid.parser

import org.sireum.util._
import java.io.File
import java.util.zip.ZipFile
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.io.IOException
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.SAXException
import brut.androlib.res.decoder.{AXmlResourceParser => BrutAXmlResourceParser}
import android.content.res.{AXmlResourceParser => ResAXmlResourceParser}
import org.sireum.jawa.ObjectType
import java.io.File
import java.io.InputStream
import java.io.IOException
import org.sireum.amandroid.util.TypedValue

/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 */ 
final case class ComponentInfo(compType: ObjectType, typ: String, exported: Boolean, enabled: Boolean, permission: Option[String])

/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 */ 
class ManifestParser{
  private val componentInfos: MSet[ComponentInfo] = msetEmpty
  private val components: MMap[ObjectType, String] = mmapEmpty
  private var packageName = ""
  private val permissions: MSet[String] = msetEmpty
  private val intentFdb: IntentFilterDataBase = new IntentFilterDataBase
  private var currentComponent: ObjectType = null
  private var applicationPermission: String = null
  private val componentPermission: MMap[ObjectType, String] = mmapEmpty
  private val componentExported: MMap[ObjectType, String] = mmapEmpty
  private val componentEnabled: MMap[ObjectType, String] = mmapEmpty
  private var currentIntentFilter: IntentFilter = null

  private var minSdkVersion = 0
  private var targetSdkVersion = 0
  private var maxSdkVersion = 0

  private def buildIntentDB(intentFilter: IntentFilter) = {
    intentFdb.updateIntentFmap(intentFilter)
  }
  /**
   * Opens the given apk file and provides the given handler with a stream for
   * accessing the contained android manifest file
   * @param apk The apk file to process
   * @param handler The handler for processing the apk file
   * 
   * adapted from Steven Arzt
   * 
   */
  def toPilarClass(str: String): ObjectType = new ObjectType(str)
  
  def loadClassesFromTextManifest(manifestIS: InputStream) = {
    try {
      val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      val doc = db.parse(manifestIS)
      var applicationEnabled = true
      val rootElement = doc.getDocumentElement()
      this.packageName = rootElement.getAttribute("package")
  
      val permissions = rootElement.getElementsByTagName("uses-permission")
      for (i <- 0 to permissions.getLength() - 1) {
        val permission = permissions.item(i).asInstanceOf[Element]
        this.permissions += permission.getAttribute("android:name")
      }
      
      val appsElement = rootElement.getElementsByTagName("application")
      for (appIdx <- 0 to appsElement.getLength() - 1) {
        val appElement: Element = appsElement.item(appIdx).asInstanceOf[Element]
        // Check whether the application is disabled
        val enabled = appElement.getAttribute("android:enabled")
        applicationEnabled = (enabled.isEmpty() || !enabled.equals("false"))
        val appperm = appElement.getAttribute("android:permission")
        if(!appperm.isEmpty())
          this.applicationPermission = appperm
        if(applicationEnabled){
          val activities = appElement.getElementsByTagName("activity")
          val receivers = appElement.getElementsByTagName("receiver")
          val services  = appElement.getElementsByTagName("service")
          val providers = appElement.getElementsByTagName("provider")
      
          for (i <- 0 to activities.getLength() - 1) {
            val activity = activities.item(i).asInstanceOf[Element]
            loadManifestEntry(activity, "activity", this.packageName)
          }
          for (i <- 0 to receivers.getLength() - 1) {
            val receiver = receivers.item(i).asInstanceOf[Element]
            loadManifestEntry(receiver, "receiver", this.packageName)
          }
          for (i <- 0 to services.getLength() - 1) {
            val service = services.item(i).asInstanceOf[Element]
            loadManifestEntry(service, "service", this.packageName)
          }
          for (i <- 0 to providers.getLength() - 1) {
            val provider = providers.item(i).asInstanceOf[Element]
            loadManifestEntry(provider, "provider", this.packageName)
          }
        }
      }
      this.components.foreach{
        case (compType, typ) =>
          val exported = this.componentExported.get(compType) match {
            case Some(tag) => 
              tag match{
                case "false" => false
                case _ => true
              }
            case None =>
              /**
               * from: http://developer.android.com/guide/topics/manifest/provider-element.html
               * For activity, receiver and service:
               * The default value depends on whether the activity contains intent filters.
               * The absence of any filters means that the activity can be invoked only by
               * specifying its exact class name. This implies that the activity is intended
               * only for application-internal use (since others would not know the class name).
               * So in this case, the default value is "false". On the other hand, the presence
               * of at least one filter implies that the activity is intended for external use,
               * so the default value is "true".
               */
              if(typ == "activity" || typ == "receiver" || typ == "service"){
                !this.intentFdb.getIntentFilters(compType).isEmpty
              } 
              /**
               * from: http://developer.android.com/guide/topics/manifest/provider-element.html
               * For provider:
               * The default value is "true" for applications that set either android:minSdkVersion
               * or android:targetSdkVersion to "16" or lower. For applications that set either of
               * these attributes to "17" or higher, the default is "false".
               */
              else if(typ == "provider") {
                this.minSdkVersion <= 16 || this.targetSdkVersion <= 16
              } else throw new RuntimeException("Wrong component type: " + typ)
          }
          val enabled = this.componentEnabled.get(compType) match {
            case Some(tag) => 
              tag match{
                case "false" => false
                case _ => true
              }
            case None =>
              true
          }
          val permission = this.componentPermission.getOrElse(compType, this.applicationPermission)
          val compermission = if(permission != null && !permission.isEmpty()) Some(permission) else None
          this.componentInfos += ComponentInfo(compType, typ, exported, enabled, compermission)
      }
    } catch {
      case ex: IOException =>
        System.err.println("Could not parse manifest: " + ex.getMessage())
        ex.printStackTrace()
      case ex: ParserConfigurationException =>
        System.err.println("Could not parse manifest: " + ex.getMessage())
        ex.printStackTrace()
      case ex: SAXException =>
        System.err.println("Could not parse manifest: " + ex.getMessage())
        ex.printStackTrace()
    }
  }

  private def loadManifestEntry(comp: Element, baseClass: String, packageName: String) = {
    val className = comp.getAttribute("android:name")
    val classType = new ObjectType(className)
    if (className.startsWith(".")){
      this.currentComponent = toPilarClass(this.packageName + className)
      this.components += (this.currentComponent -> baseClass)
    }
    else if (className.substring(0, 1).equals(className.substring(0, 1).toUpperCase())){
      this.currentComponent = toPilarClass(this.packageName + "." + className)
      this.components += (this.currentComponent -> baseClass)
    }
    else if (this.packageName != "" && !className.contains(".")){
      this.currentComponent = toPilarClass(this.packageName + "." + className)
      this.components += (this.currentComponent -> baseClass)
    }
    else {
      this.currentComponent = toPilarClass(className)
      this.components += (this.currentComponent -> baseClass)
    }
    val permission = comp.getAttribute("android:permission")
    if (!permission.isEmpty()){
      this.componentPermission += (classType -> permission)
    }
    val exported = comp.getAttribute("android:exported")
    if(!exported.isEmpty()){
      this.componentExported += (classType -> exported)
    }
    val enabled = comp.getAttribute("android:enabled")
    if(!enabled.isEmpty()){
      this.componentEnabled += (classType -> enabled)
    }
    val intentfs = comp.getElementsByTagName("intent-filter")
    for (i <- 0 to intentfs.getLength() - 1) {
      val intentfilter = intentfs.item(i).asInstanceOf[Element]
      if(this.currentComponent != null){
        this.currentIntentFilter = new IntentFilter(this.currentComponent)
        buildIntentDB(this.currentIntentFilter)
        val actions = intentfilter.getElementsByTagName("action")
        for (a <- 0 to actions.getLength() - 1) {
          if (this.currentIntentFilter != null){
            val action = actions.item(a).asInstanceOf[Element]
            val name = action.getAttribute("android:name")
            val intentF = this.currentIntentFilter
            intentF.addAction(name)              
          }
        }
        val categories = intentfilter.getElementsByTagName("category")
        for (c <- 0 to categories.getLength() - 1) {
          if (this.currentIntentFilter != null){
            val category = categories.item(c).asInstanceOf[Element]
            val name = category.getAttribute("android:name")
            val intentF = this.currentIntentFilter
            intentF.addCategory(name)              
          }
        }
        val datas = intentfilter.getElementsByTagName("data")
        for (d <- 0 to datas.getLength() - 1) {
          if (this.currentIntentFilter != null){
            val data = datas.item(d).asInstanceOf[Element]
            val scheme = if(data.hasAttribute("android:scheme")) data.getAttribute("android:scheme") else null
            val host = if(data.hasAttribute("android:host"))data.getAttribute("android:host") else null
            val port = if(data.hasAttribute("android:port"))data.getAttribute("android:port") else null
            val path = if(data.hasAttribute("android:path"))data.getAttribute("android:path") else null
            val pathPrefix = if(data.hasAttribute("android:pathPrefix"))data.getAttribute("android:pathPrefix") else null
            val pathPattern = if(data.hasAttribute("android:pathPattern"))data.getAttribute("android:pathPattern") else null
            val mimeType = if(data.hasAttribute("android:mimeType"))data.getAttribute("android:mimeType") else null
            val intentF = this.currentIntentFilter
            intentF.modData(scheme, host, port, path, pathPrefix, pathPattern, mimeType)
          }
        }
      }
    }
    
    
  }

  def getComponentClasses: ISet[ObjectType] = this.components.map(_._1).toSet

  def getComponentInfos: ISet[ComponentInfo] = this.componentInfos.toSet

  def getPermissions: ISet[String] = this.permissions.toSet

  def getPackageName = this.packageName

  def getIntentDB = this.intentFdb
}

object ManifestParser {
  def loadPackageName(apk: File): String = {
    var pkg: String = ""
    AndroidXMLParser.handleAndroidXMLFiles(apk, Set("AndroidManifest.xml"), new AndroidXMLHandler() {
      
      override def handleXMLFile(fileName: String, fileNameFilter: Set[String], stream: InputStream) = {
        try {
          if (fileNameFilter.contains(fileName)){
            pkg = getPackageNameFromManifest(stream)
          }
        }
        catch {
          case ex: IOException =>
            System.err.println("Could not read AndroidManifest file: " + ex.getMessage())
            ex.printStackTrace()
        }
      }
      
    })
    
    pkg
  }
  
  def loadSdkVersionFromManifestFile(apk: File): (Int, Int, Int) = {
    var min: Int = 1
    var target: Int = min
    var max: Int = target
    AndroidXMLParser.handleAndroidXMLFiles(apk, Set("AndroidManifest.xml"), new AndroidXMLHandler() {
      
      override def handleXMLFile(fileName: String, fileNameFilter: Set[String], stream: InputStream) = {
        try {
          if (fileNameFilter.contains(fileName)){
            val (mint, targett, maxt) = getSdkVersionFromBinaryManifest(stream)
            min = mint
            target = targett
            max = maxt
          }
        }
        catch {
          case ex: IOException =>
            System.err.println("Could not read AndroidManifest file: " + ex.getMessage())
            ex.printStackTrace()
        }
      }
      
    })
    (min, target, max)
  }
  
  protected def getPackageNameFromManifest(manifestIS: InputStream): String = {
    var pkg: String = ""
    try {
      val parser = new ResAXmlResourceParser()
      parser.open(manifestIS)
      var typ = parser.next()
      while (typ != 0x00000001) { // XmlPullParser.END_DOCUMENT
        typ match {
          case 0x00000000 => // XmlPullParser.START_DOCUMENT
          case 0x00000002 => //XmlPullParser.START_TAG
            val tagName = parser.getName
            if(tagName.equals("manifest")){
              pkg = getAttributeValue(parser, "package")
              if(pkg == null) pkg = ""
            }
          case 0x00000003 => //XmlPullParser.END_TAG
          case 0x00000004 => //XmlPullParser.TEXT
        }
        typ = parser.next()
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
    }
    pkg
  }
  
  protected def getSdkVersionFromBinaryManifest(manifestIS: InputStream): (Int, Int, Int) = {
    var min: Int = 1
    var target: Int = min
    var max: Int = target
    try {
      val parser = new ResAXmlResourceParser()
      parser.open(manifestIS)
      var typ = parser.next()
      while (typ != 0x00000001) { // XmlPullParser.END_DOCUMENT
         typ match {
          case 0x00000000 => // XmlPullParser.START_DOCUMENT
          case 0x00000002 => //XmlPullParser.START_TAG
            val tagName = parser.getName()
            if (tagName.equals("uses-sdk")){
              var attrValue = getAttributeValue(parser, "minSdkVersion")
              if (attrValue != null) min = attrValue.toInt
              attrValue = getAttributeValue(parser, "targetSdkVersion")
              if (attrValue != null) target = attrValue.toInt
              attrValue = getAttributeValue(parser, "maxSdkVersion")
              if (attrValue != null) max = attrValue.toInt
            }
            
          case 0x00000003 => //XmlPullParser.END_TAG
          case 0x00000004 => //XmlPullParser.TEXT
        }
        typ = parser.next()
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
    } finally {
      if(min < 1) min = 1
      if(target < min) target = min
      if(max < target) max = target
    }
    (min, target, max)
  }
  
  private def getAttributeValue(parser: ResAXmlResourceParser, attributeName: String): String = {
    val count = parser.getAttributeCount
    for (i <- 0 to count - 1){ 
      if (parser.getAttributeName(i).equals(attributeName))
        return getAttributeValue(parser, i)
    }
    null
  }
  
  private def getAttributeValue(parser: ResAXmlResourceParser,index: Int): String = {
    val typ: Int = parser.getAttributeValueType(index)
    val data: Int = parser.getAttributeValueData(index)
    if (typ == TypedValue.TYPE_STRING) {
      return parser.getAttributeValue(index);
    }
    if (typ==TypedValue.TYPE_ATTRIBUTE) {
      val pkg = getPackage(data)
      return f"?$pkg%s$data%08X"
    }
    if (typ==TypedValue.TYPE_REFERENCE) {
      val pkg = getPackage(data)
      return f"@$pkg%s$data%08X"
    }
    if (typ==TypedValue.TYPE_FLOAT) {
      return String.valueOf(data.toFloat)
    }
    if (typ==TypedValue.TYPE_INT_HEX) {
      return f"0x$data%08X"
    }
    if (typ==TypedValue.TYPE_INT_BOOLEAN) {
      return if(data!=0)"true"else"false"
    }
    if (typ==TypedValue.TYPE_DIMENSION) {
      return complexToFloat(data) + DIMENSION_UNITS(data & TypedValue.COMPLEX_UNIT_MASK)
    }
    if (typ == TypedValue.TYPE_FRACTION) {
      return complexToFloat(data) + FRACTION_UNITS(data & TypedValue.COMPLEX_UNIT_MASK)
    }
    if (typ >= TypedValue.TYPE_FIRST_COLOR_INT && typ<=TypedValue.TYPE_LAST_COLOR_INT) {
      return f"#$data%08X"
    }
    if (typ >= TypedValue.TYPE_FIRST_INT && typ<=TypedValue.TYPE_LAST_INT) {
      return String.valueOf(data)
    }
    return f"<0x$data%X, type 0x$typ%02X>"
  }
  
  private def getPackage(id: Int): String = {
    if (id>>>24==1) {
      return "android:"
    }
    return ""
  }
  
  def complexToFloat(complex: Int): Float = {
    return (complex & 0xFFFFFF00)*RADIX_MULTS((complex>>4) & 3)
  }
  
  private final def RADIX_MULTS = List(
    0.00390625F,3.051758E-005F,1.192093E-007F,4.656613E-010F
  )
  private final def DIMENSION_UNITS = List(
    "px","dip","sp","pt","in","mm","",""
  )
  private final def FRACTION_UNITS = List(
    "%","%p","","","","","",""
  )
}