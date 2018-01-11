package bootstrap.liftweb

import org.springframework.context.annotation.Configuration
import net.liftweb.common.Loggable
import org.springframework.context.ApplicationContextAware
import org.springframework.beans.factory.InitializingBean
import com.normation.rudder.web.snippet.IndexExtension
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

@Configuration
class BrandingConf extends Loggable with  ApplicationContextAware with InitializingBean {
  logger.warn("hello darkness my old friend")
  import com.normation.plugins.{ SnippetExtensionRegister }
   var appContext : Option[ApplicationContext] = None

      @Bean def groupReportTab = new IndexExtension()
    override def afterPropertiesSet() : Unit = {
     logger.error("test")
         for {
           context <- appContext
           ext = context.getBean(classOf[SnippetExtensionRegister])
         } yield {
     logger.error("test")
           ext.register(groupReportTab)
         }

    }
      override def setApplicationContext(applicationContext:ApplicationContext) = {
    appContext = Some(applicationContext)
  }

}