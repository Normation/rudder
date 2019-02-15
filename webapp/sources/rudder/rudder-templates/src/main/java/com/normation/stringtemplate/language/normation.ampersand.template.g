header {
/*
 [The "BSD licence"]
 Copyright (c) 2003-2004 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 Modification 2010, Nicolas Charles
*/

	package com.normation.stringtemplate.language;
    import org.antlr.stringtemplate.language.*;
    import org.antlr.stringtemplate.*;
    import java.io.*;
}

/** A parser used to break up a single template into chunks, text literals
 *  and attribute expressions.
 */
class NormationAmpersandTemplateParser extends Parser;

{
protected StringTemplate self;

public void reportError(RecognitionException e) {
    StringTemplateGroup group = self.getGroup();
    if ( group==StringTemplate.defaultGroup ) {
        self.error("template parse error; template context is "+self.getEnclosingInstanceStackString(), e);
    }
    else {
        self.error("template parse error in group "+self.getGroup().getName()+" line "+self.getGroupFileLine()+"; template context is "+self.getEnclosingInstanceStackString(), e);
    }
}
}

template[StringTemplate self]
{
	this.self = self;
}
    :   (   s:LITERAL  {self.addChunk(new StringRef(self,s.getText()));}
        |   nl:NEWLINE
        	{
            if ( LA(1)!=ELSE && LA(1)!=ENDIF ) {
            	self.addChunk(new NewlineRef(self,nl.getText()));
            }
        	}
        |   action[self]
        )*
    ;

action[StringTemplate self]
    :   a:ACTION
        {
        String indent = ((ChunkToken)a).getIndentation();
        ASTExpr c = self.parseAction(a.getText());
        c.setIndentation(indent);
        self.addChunk(c);
        }

    |   i:IF
        {
        ConditionalExpr c = (ConditionalExpr)self.parseAction(i.getText());
        // create and precompile the subtemplate
        StringTemplate subtemplate =
        	new StringTemplate(self.getGroup(), null);
        subtemplate.setEnclosingInstance(self);
        subtemplate.setName(i.getText()+"_subtemplate");
        self.addChunk(c);
        }

        template[subtemplate] {if ( c!=null ) c.setSubtemplate(subtemplate);}

        (   ei:ELSEIF
            {
             ASTExpr ec = self.parseAction(ei.getText());
             // create and precompile the subtemplate
             StringTemplate elseIfSubtemplate =
                 new StringTemplate(self.getGroup(), null);
             elseIfSubtemplate.setEnclosingInstance(self);
             elseIfSubtemplate.setName(ei.getText()+"_subtemplate");
            }

            template[elseIfSubtemplate]

            {if ( c!=null ) c.addElseIfSubtemplate(ec, elseIfSubtemplate);}
        )*

        (   ELSE
            {
            // create and precompile the subtemplate
            StringTemplate elseSubtemplate =
         		new StringTemplate(self.getGroup(), null);
            elseSubtemplate.setEnclosingInstance(self);
            elseSubtemplate.setName("else_subtemplate");
            }

            template[elseSubtemplate]
            {if ( c!=null ) c.setElseSubtemplate(elseSubtemplate);}
        )?

        ENDIF

	|	rr:REGION_REF
    	{
    		// define implicit template and
    		// convert <@r()> to <region__enclosingTemplate__r()>
			String regionName = rr.getText();
			String mangledRef = null;
			boolean err = false;
			// watch out for <@super.r()>; that does NOT def implicit region
			// convert to <super.region__enclosingTemplate__r()>
			if ( regionName.startsWith("super.") ) {
				//System.out.println("super region ref "+regionName);
				String regionRef =
					regionName.substring("super.".length(),regionName.length());
				String templateScope =
					self.getGroup().getUnMangledTemplateName(self.getName());
				StringTemplate scopeST = self.getGroup().lookupTemplate(templateScope);
				if ( scopeST==null ) {
					self.getGroup().error("reference to region within undefined template: "+
						templateScope);
					err=true;
				}
				if ( !scopeST.containsRegionName(regionRef) ) {
					self.getGroup().error("template "+templateScope+" has no region called "+
						regionRef);
					err=true;
				}
				else {
					mangledRef =
						self.getGroup().getMangledRegionName(templateScope,regionRef);
					mangledRef = "super."+mangledRef;
				}
			}
			else {
				//System.out.println("region ref "+regionName);
				StringTemplate regionST =
                    self.getGroup().defineImplicitRegionTemplate(self,regionName);
                mangledRef = regionST.getName();
            }

			if ( !err ) {
				// treat as regular action: mangled template include
				String indent = ((ChunkToken)rr).getIndentation();
				ASTExpr c = self.parseAction(mangledRef+"()");
				c.setIndentation(indent);
				self.addChunk(c);
			}
    	}

    |	rd:REGION_DEF
		{
			String combinedNameTemplateStr = rd.getText();
			int indexOfDefSymbol = combinedNameTemplateStr.indexOf("::=");
			if ( indexOfDefSymbol>=1 ) {
				String regionName = combinedNameTemplateStr.substring(0,indexOfDefSymbol);
				String template =
					combinedNameTemplateStr.substring(indexOfDefSymbol+3,
						combinedNameTemplateStr.length());
				StringTemplate regionST =
                    self.getGroup().defineRegionTemplate(self,
									regionName,
									template,
									StringTemplate.REGION_EMBEDDED);
				// treat as regular action: mangled template include
				String indent = ((ChunkToken)rd).getIndentation();
				ASTExpr c = self.parseAction(regionST.getName()+"()");
				c.setIndentation(indent);
				self.addChunk(c);
			}
			else {
				self.error("embedded region definition screwed up");
			}
		}
    ;

/** Break up an input text stream into chunks of either plain text
 *  or template actions in "&...&".  Treat IF and ENDIF tokens
 *  specially.
 */
class NormationAmpersandTemplateLexer extends Lexer;

options {
    k=7; // see "&endif&"
    charVocabulary = '\u0001'..'\uFFFE';
}

{
protected String currentIndent = null;
protected StringTemplate self;

public NormationAmpersandTemplateLexer(StringTemplate self, Reader r) {
	this(r);
	this.self = self;
}

public void reportError(RecognitionException e) {
	self.error("&...& chunk lexer error", e);
}

protected boolean upcomingELSE(int i) throws CharStreamException {
 	return LA(i)=='&'&&LA(i+1)=='e'&&LA(i+2)=='l'&&LA(i+3)=='s'&&LA(i+4)=='e'&&
 	       LA(i+5)=='&';
}

protected boolean upcomingENDIF(int i) throws CharStreamException {
	return LA(i)=='&'&&LA(i+1)=='e'&&LA(i+2)=='n'&&LA(i+3)=='d'&&LA(i+4)=='i'&&
	       LA(i+5)=='f'&&LA(i+6)=='&';
}

protected boolean upcomingAtEND(int i) throws CharStreamException {
	return LA(i)=='&'&&LA(i+1)=='@'&&LA(i+2)=='e'&&LA(i+3)=='n'&&LA(i+4)=='d'&&LA(i+5)=='&';
}

protected boolean upcomingNewline(int i) throws CharStreamException {
	return (LA(i)=='\r'&&LA(i+1)=='\n')||LA(i)=='\n';
}
}

LITERAL
    :   {LA(1)!='\r'&&LA(1)!='\n'}?
        ( options { generateAmbigWarnings=false;}
          {
          int loopStartIndex=text.length();
          int col=getColumn();
          }
        : '\\'! '&'  // allow escaped delimiter
        | '\\'! '\\' // always replace \\ with \
        | '\\' ~'&'  // otherwise ignore escape char
        | ind:INDENT
          {
          if ( col==1 && LA(1)=='&' ) {
              // store indent in ASTExpr not in a literal
              currentIndent=ind.getText();
			  text.setLength(loopStartIndex); // reset length to wack text
          }
          else currentIndent=null;
          }
        | ~('&'|'\r'|'\n')
        )+
        {if (($getText).length()==0) {$setType(Token.SKIP);}} // success indent?
    ;

NEWLINE
    :	('\r')? '\n' {newline(); currentIndent=null;}
    ;

ACTION
   	options {
   		generateAmbigWarnings=false; // &EXPR& is ambig with &!..!&
	}
{
    int startCol = getColumn();
}
    :	// Match escapes not in a string like <\n\ufea5>
		{StringBuffer buf = new StringBuffer(); char uc = '\u0000';}
    	'&'! (uc=ESC_CHAR {buf.append(uc);} )+'&'!
    	{$setText(buf.toString()); $setType(LITERAL);}
    | 	COMMENT {$setType(Token.SKIP);}
    |   (
    	options {
    		generateAmbigWarnings=false; //	 &EXPR& is ambig with &endif& etc...
		}
	:	'&'! "if" (' '!)* "(" IF_EXPR ")" '&'! {$setType(TemplateParser.IF);}
        ( ('\r'!)? '\n'! {newline();})? // ignore any newline right after an IF
	|	'&'! "elseif" (' '!)* "(" IF_EXPR ")" '&'! {$setType(TemplateParser.ELSEIF);}
        ( ('\r'!)? '\n'! {newline();})? // ignore any newline right after an IF
    |   '&'! "else" '&'!         {$setType(TemplateParser.ELSE);}
        ( ('\r'!)? '\n'! {newline();})? // ignore any newline right after an ELSE
    |   '&'! "endif" '&'!        {$setType(TemplateParser.ENDIF);}
        ( {startCol==1}? ('\r'!)? '\n'! {newline();})? // ignore after ENDIF if on line by itself

    |   // match &@foo()& => foo
    	// match &@foo&...&@end& => foo::=...
        '&'! '@'! (~('&'|'('))+
    	(	"()"! '&'! {$setType(TemplateParser.REGION_REF);}
    	|   '&'!
    		{$setType(TemplateParser.REGION_DEF);
    		String t=$getText;
    		$setText(t+"::=");
    		}
        	( options {greedy=true;} : ('\r'!)? '\n'! {newline();})?
    		{boolean atLeft = false;}
        	(
        		options {greedy=true;} // handle greedy=false with predicate
        	:	{!(upcomingAtEND(1)||(upcomingNewline(1)&&upcomingAtEND(2)))}?
        		(	('\r')? '\n' {newline(); atLeft = true;}
       			|	. {atLeft = false;}
       			)
       		)+
        	( ('\r'!)? '\n'! {newline(); atLeft = true;} )?
			( "&@end&"!
			| . {self.error("missing region "+t+" &@end& tag");}
			)
        	( {atLeft}? ('\r'!)? '\n'! {newline();})?
        )

    |   '&'! EXPR '&'!  // (Can't start with '!', which would mean comment)
    	)
    	{
        ChunkToken t = new ChunkToken(_ttype, $getText, currentIndent);
        $setToken(t);
    	}
    ;

protected
EXPR:   ( ESC
        | ('\r')? '\n' {newline();}
        | SUBTEMPLATE
        | ('='|'+') TEMPLATE
        | ('='|'+') SUBTEMPLATE
        | ('='|'+') ~('"'|'<'|'{')
        | ~'&'
        )+
    ;

protected
TEMPLATE
	:	'"' ( ESC | ~'"' )* '"'
	|	"<<"
	 	(options {greedy=true;}:('\r'!)?'\n'! {newline();})? // consume 1st \n
		(	options {greedy=false;}  // stop when you see the >>
		:	{LA(3)=='>'&&LA(4)=='>'}? '\r'! '\n'! {newline();} // kill last \r\n
		|	{LA(2)=='>'&&LA(3)=='>'}? '\n'! {newline();}       // kill last \n
		|	('\r')? '\n' {newline();}                          // else keep
		|	.
		)*
        ">>"
	;

protected
IF_EXPR:( ESC
        | ('\r')? '\n' {newline();}
        | SUBTEMPLATE
        | NESTED_PARENS
        | ~')'
        )+
    ;

protected
ESC_CHAR returns [char uc='\u0000']
	:	"\\n"! {uc = '\n';}
	|	"\\r"! {uc = '\r';}
	|	"\\t"! {uc = '\t';}
	|	"\\ "! {uc = ' ';}
	|	"\\u"! a:HEX! b:HEX! c:HEX! d:HEX!
		{uc = (char)Integer.parseInt(a.getText()+b.getText()+c.getText()+d.getText(), 16);}
	;

protected
ESC :   '\\' . // ('&'|'n'|'t'|'"'|'\''|':'|'{'|'}')
    ;

protected
HEX	:	'0'..'9'|'A'..'F'|'a'..'f'
	;

protected
SUBTEMPLATE
    :    '{' (SUBTEMPLATE|ESC|~'}')* '}'
    ;

protected
NESTED_PARENS
    :    '(' (options {greedy=false;}:NESTED_PARENS|ESC|~')')+ ')'
    ;

protected
INDENT
    :   ( options {greedy=true;}: ' ' | '\t')+
    ;

protected
COMMENT
{
    int startCol = getColumn();
}
    :   "&!"
    	(	options {greedy=false;}
    	:	('\r')? '\n' {newline();}
    	|	.
    	)*
    	"!&" ( {startCol==1}? ('\r')? '\n' {newline();} )?
    ;

