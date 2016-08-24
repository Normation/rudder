package com.normation.stringtemplate.language.formatter;

import org.antlr.stringtemplate.AttributeRenderer;
import org.joda.time.LocalTime;


public class LocalTimeRenderer implements AttributeRenderer {

	public String toString(Object arg0) {
		return arg0.toString();
	}

	public String toString(Object date, String formatName) {
		 if (formatName.equals("cfengine_localtime"))
			 return ((LocalTime)date).toString("HH:mm");
		 else
			 throw new IllegalArgumentException("Unsupported format name for date " + date);
	}

}
