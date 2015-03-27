/*
Copyright (c) 2013-2014 Fengguo Wei & Sankardas Roy, Kansas State University.        
All rights reserved. This program and the accompanying materials      
are made available under the terms of the Eclipse Public License v1.0 
which accompanies this distribution, and is available at              
http://www.eclipse.org/legal/epl-v10.html                             
*/
package org.sireum.amandroid.alir.pta.reachingFactsAnalysis

import org.sireum.util._
import org.sireum.jawa.JawaProcedure
import org.sireum.jawa.alir.pta.reachingFactsAnalysis.RFAFact
import org.sireum.jawa.alir.Context
import org.sireum.amandroid.alir.pta.reachingFactsAnalysis.model.AndroidModelCallHandler
import org.sireum.jawa.alir.pta.PTAResult

/**
 * @author <a href="mailto:fgwei@k-state.edu">Fengguo Wei</a>
 * @author <a href="mailto:sroy@k-state.edu">Sankardas Roy</a>
 */ 
object AndroidReachingFactsAnalysisHelper {
	
	def isModelCall(calleeProc : JawaProcedure) : Boolean = {
    AndroidModelCallHandler.isModelCall(calleeProc)
  }
  
  def doModelCall(s : PTAResult, calleeProc : JawaProcedure, args : List[String], retVars : Seq[String], currentContext : Context) : (ISet[RFAFact], ISet[RFAFact]) = {
    AndroidModelCallHandler.doModelCall(s, calleeProc, args, retVars, currentContext)
  }
  
  def isICCCall(calleeProc : JawaProcedure) : Boolean = {
    AndroidModelCallHandler.isICCCall(calleeProc)
  }
  
  def doICCCall(s : PTAResult, calleeProc : JawaProcedure, args : List[String], retVars : Seq[String], currentContext : Context) : (ISet[RFAFact], ISet[JawaProcedure]) = {
    AndroidModelCallHandler.doICCCall(s, calleeProc, args, retVars, currentContext)
  }
}