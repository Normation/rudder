package com.normation.rudder.repository.jdbc
import scala.util.Random
import org.joda.time.DateTime
import java.io.IOException

object DataGenerator {

  
  def main(args: Array[String]) = {
    
  
    val severities = Seq[String]("result_success", "result_success", "result_success", "result_success", "log_info", "log_info", "log_info", "result_repaired")
  
    val crIds = Seq[String]("hasPolicyServer-root@@common-root@@16"
                          , "e2369d5e-a14a-4479-895f-76c831e27557@@d49900ab-0b94-4ea7-ad57-bda1f6a051c8@@27"
                          , "3ef8ed69-1a86-4998-9f95-17dff831f483@@47046b8fdca2-4cfb-949b-9416f0e9abf6@@13"
                          , "e2369d5e-a14a-4479-895f-76c831e27557@@de2be386-0730-42f6-8d84-24d9e2d9c0e9@@27"
                          , "e2369d5e-a14a-4479-895f-76c831e27557@@258c793b-d87e-4cea-a05c-db91866da131@@27"
                          ,"e2369d5e-a14a-4479-895f-76c831e27557@@738784d8-ab44-4c8c-8312-67e4b57e0fc3@@27"
                          , "e2369d5e-a14a-4479-895f-76c831e27557@@9eddb966-3270-4d74-887f-8942b8d0daa9@@27"
                          , "e2369d5e-a14a-4479-895f-76c831e27557@@3f229756-54b8-4772-a487-b2061127f55b@@27"
                          , "283e0c91-8177-4fd5-b66a-257ba61801f8@@bea99b60-a4e6-4197-bc5a-6148afbaf72a@@3")
  
    val componentKeys = Seq[String]("common@@StartRun"
                                  , "Time synchronization (NTP)@@None"
                                  , "Hardware clock (RTC)@@None"
                                  , "Time zone@@None"
                                  , "Update@@None"
                                  , "Security parameters@@None"
                                  , "Log system for reports@@None"
                                  , "inventory@@None"
                                  , "SSH key@@nperron"
                                  , "SSH key@@jclarke"
                                  , "SSH key@@mcerda"
                                  , "Permissions@@mcerda"
                                  , "sudoersFile@@None"
                                  , "dnsConfiguration@@None"
                                  , "Permission adjustment@@/etc/munin/munin-node.conf"
                                  , "Post-modification hook@@/etc/munin/munin-node.conf"
                                  , "Users@@nperron"
                                  , "Users@@jclarke"
                                  , "motdConfiguration@@None"
                                  , "Debian/Ubuntu packages@@rsync"
                                  , "Debian/Ubuntu packages@@tree"
                                  , "Debian/Ubuntu packages@@htop"
                                  , "Debian/Ubuntu packages@@vim"
                                  )
  
  
  
    val nodeIds = Seq[String]("db88c903-4c0e-4905-bd38-084218d1557a", "4c3e0270-a50a-4c63-9f2a-a48c7591b102", "8dd6e44f-8e3a-4fb7-8656-f2312785e829", "2228eff2-6bf2-4672-b150-0accdc2d2f0f")
  
  
    val endExecution = "R: @@Common@@log_info@@hasPolicyServer-root@@common-root@@16@@common@@EndRun@@%s+01:00##%s@#End execution"
  
    val startExecution = "R: @@Common@@log_info@@hasPolicyServer-root@@common-root@@16@@common@@StartRun@@%s+01:00##%s@#Start execution"
  
    // %1$s = severity
    // %2$s = cr
    // %3$s = component
    // %4$s = date
    // %5$s = node
    val tml = "R: @@Common@@%1$s@@%2$s@@%3$s@@%4$s+01:00##%5$s@#random text"
      
    val rnd = new Random(37)
  
    for (i <- 0 until 10000) {
      val executionDateTime = new DateTime()
      val node = nodeIds(rnd.nextInt(4))
    
      val startMsg = startExecution.format(executionDateTime.toString("yyyy-MM-dd HH:mm:ss"), node)
      val endMsg = endExecution.format(executionDateTime.toString("yyyy-MM-dd HH:mm:ss"), node)
    
      executeLogger(startMsg)
      for (j <- 0 until 18) {
         val str = tml.format(
             severities(rnd.nextInt(8))
           , crIds(rnd.nextInt(9))
           , componentKeys(rnd.nextInt(23))
           , executionDateTime.toString("yyyy-MM-dd HH:mm:ss")
           , node
         )
         executeLogger(str)
         
      }
      executeLogger(endMsg)
      println("Sent message to %s %s".format(node, new DateTime()))
      Thread.sleep(100)
      
    
    }
  }
  
  private[this] def executeLogger(msg : String) : Unit = {
    val process = Runtime.getRuntime().exec(Array[String]("/usr/bin/logger", msg))
    if (process.waitFor() != 0) {
      throw new IOException("Couldn't syslog " + msg)
    }
  }
}