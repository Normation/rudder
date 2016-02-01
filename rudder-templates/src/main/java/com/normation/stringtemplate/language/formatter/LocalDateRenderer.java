package com.normation.stringtemplate.language.formatter;

import org.antlr.stringtemplate.AttributeRenderer;
import org.joda.time.LocalDate;


public class LocalDateRenderer implements AttributeRenderer {

	public String toString(Object arg0) {
		return arg0.toString();
	}

	public String toString(Object date, String formatName) {
		 if (formatName.equals("cfengine_localdate"))
			 return ((LocalDate)date).toString("yyyy:MM:dd");
		 else
			 throw new IllegalArgumentException("Unsupported format name for date " + date);
	}

}
