package com.normation.stringtemplate.language;

import org.antlr.stringtemplate.StringTemplateErrorListener;

public class NormationStringTemplateErrorListener implements
		StringTemplateErrorListener {


	public void error(String arg0, Throwable arg1) {
		System.out.println(arg0);
		System.out.println(arg1);
		
		

	}


	public void warning(String arg0) {
		System.out.println(arg0);
	}

}
