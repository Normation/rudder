package com.normation.stringtemplate.language.formatter;

import org.antlr.stringtemplate.AttributeRenderer;
import org.joda.time.DateTime;


public class DateRenderer implements AttributeRenderer {

	public String toString(Object arg0) {
		return arg0.toString();
	}

	public String toString(Object date, String formatName) {
		 if (formatName.equals("cfengine_datetime"))
			 return ((DateTime)date).toString("yyyy:MM:dd:hh:mm:ss");
		 else
			 throw new IllegalArgumentException("Unsupported format name for date " + date);
	}

}
