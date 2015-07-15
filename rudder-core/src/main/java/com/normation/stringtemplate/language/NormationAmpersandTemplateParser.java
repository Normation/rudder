// $ANTLR 2.7.7 (20060906): "normation.ampersand.template.g" -> "NormationAmpersandTemplateParser.java"$

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

import antlr.TokenBuffer;
import antlr.TokenStreamException;
import antlr.Token;
import antlr.TokenStream;
import antlr.RecognitionException;
import antlr.NoViableAltException;
import antlr.ParserSharedInputState;
import antlr.collections.impl.BitSet;

/** A parser used to break up a single template into chunks, text literals
 *  and attribute expressions.
 */
public class NormationAmpersandTemplateParser extends antlr.LLkParser       implements NormationAmpersandTemplateParserTokenTypes
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

protected NormationAmpersandTemplateParser(TokenBuffer tokenBuf, int k) {
  super(tokenBuf,k);
  tokenNames = _tokenNames;
}

public NormationAmpersandTemplateParser(TokenBuffer tokenBuf) {
  this(tokenBuf,1);
}

protected NormationAmpersandTemplateParser(TokenStream lexer, int k) {
  super(lexer,k);
  tokenNames = _tokenNames;
}

public NormationAmpersandTemplateParser(TokenStream lexer) {
  this(lexer,1);
}

public NormationAmpersandTemplateParser(ParserSharedInputState state) {
  super(state,1);
  tokenNames = _tokenNames;
}

	public final void template(
		StringTemplate self
	) throws RecognitionException, TokenStreamException {
		
		Token  s = null;
		Token  nl = null;
		
			this.self = self;
		
		
		try {      // for error handling
			{
			_loop3:
			do {
				switch ( LA(1)) {
				case LITERAL:
				{
					s = LT(1);
					match(LITERAL);
					self.addChunk(new StringRef(self,s.getText()));
					break;
				}
				case NEWLINE:
				{
					nl = LT(1);
					match(NEWLINE);
					
					if ( LA(1)!=ELSE && LA(1)!=ENDIF ) {
						self.addChunk(new NewlineRef(self,nl.getText()));
					}
						
					break;
				}
				case ACTION:
				case IF:
				case REGION_REF:
				case REGION_DEF:
				{
					action(self);
					break;
				}
				default:
				{
					break _loop3;
				}
				}
			} while (true);
			}
		}
		catch (RecognitionException ex) {
			reportError(ex);
			recover(ex,_tokenSet_0);
		}
	}
	
	public final void action(
		StringTemplate self
	) throws RecognitionException, TokenStreamException {
		
		Token  a = null;
		Token  i = null;
		Token  ei = null;
		Token  rr = null;
		Token  rd = null;
		
		try {      // for error handling
			switch ( LA(1)) {
			case ACTION:
			{
				a = LT(1);
				match(ACTION);
				
				String indent = ((ChunkToken)a).getIndentation();
				ASTExpr c = self.parseAction(a.getText());
				c.setIndentation(indent);
				self.addChunk(c);
				
				break;
			}
			case IF:
			{
				i = LT(1);
				match(IF);
				
				ConditionalExpr c = (ConditionalExpr)self.parseAction(i.getText());
				// create and precompile the subtemplate
				StringTemplate subtemplate =
					new StringTemplate(self.getGroup(), null);
				subtemplate.setEnclosingInstance(self);
				subtemplate.setName(i.getText()+"_subtemplate");
				self.addChunk(c);
				
				template(subtemplate);
				if ( c!=null ) c.setSubtemplate(subtemplate);
				{
				_loop6:
				do {
					if ((LA(1)==ELSEIF)) {
						ei = LT(1);
						match(ELSEIF);
						
						ASTExpr ec = self.parseAction(ei.getText());
						// create and precompile the subtemplate
						StringTemplate elseIfSubtemplate =
						new StringTemplate(self.getGroup(), null);
						elseIfSubtemplate.setEnclosingInstance(self);
						elseIfSubtemplate.setName(ei.getText()+"_subtemplate");
						
						template(elseIfSubtemplate);
						if ( c!=null ) c.addElseIfSubtemplate(ec, elseIfSubtemplate);
					}
					else {
						break _loop6;
					}
					
				} while (true);
				}
				{
				switch ( LA(1)) {
				case ELSE:
				{
					match(ELSE);
					
					// create and precompile the subtemplate
					StringTemplate elseSubtemplate =
							new StringTemplate(self.getGroup(), null);
					elseSubtemplate.setEnclosingInstance(self);
					elseSubtemplate.setName("else_subtemplate");
					
					template(elseSubtemplate);
					if ( c!=null ) c.setElseSubtemplate(elseSubtemplate);
					break;
				}
				case ENDIF:
				{
					break;
				}
				default:
				{
					throw new NoViableAltException(LT(1), getFilename());
				}
				}
				}
				match(ENDIF);
				break;
			}
			case REGION_REF:
			{
				rr = LT(1);
				match(REGION_REF);
				
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
					
				break;
			}
			case REGION_DEF:
			{
				rd = LT(1);
				match(REGION_DEF);
				
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
						
				break;
			}
			default:
			{
				throw new NoViableAltException(LT(1), getFilename());
			}
			}
		}
		catch (RecognitionException ex) {
			reportError(ex);
			recover(ex,_tokenSet_1);
		}
	}
	
	
	public static final String[] _tokenNames = {
		"<0>",
		"EOF",
		"<2>",
		"NULL_TREE_LOOKAHEAD",
		"LITERAL",
		"NEWLINE",
		"ACTION",
		"IF",
		"ELSEIF",
		"ELSE",
		"ENDIF",
		"REGION_REF",
		"REGION_DEF",
		"EXPR",
		"TEMPLATE",
		"IF_EXPR",
		"ESC_CHAR",
		"ESC",
		"HEX",
		"SUBTEMPLATE",
		"NESTED_PARENS",
		"INDENT",
		"COMMENT"
	};
	
	private static final long[] mk_tokenSet_0() {
		long[] data = { 1792L, 0L};
		return data;
	}
	public static final BitSet _tokenSet_0 = new BitSet(mk_tokenSet_0());
	private static final long[] mk_tokenSet_1() {
		long[] data = { 8176L, 0L};
		return data;
	}
	public static final BitSet _tokenSet_1 = new BitSet(mk_tokenSet_1());
	
	}
