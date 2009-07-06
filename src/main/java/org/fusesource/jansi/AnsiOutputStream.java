/**
 *  Copyright (C) 2008 Progress Software, Inc. All rights reserved.
 *  http://fusesource.com
 *
 *  The software in this package is published under the terms of the AGPL license
 *  a copy of which has been included with this distribution in the license.txt file.
 */
package org.fusesource.jansi;

import java.io.FilterOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * A ANSI output stream extracts ANSI escape codes written to 
 * an output stream. 
 * 
 * For more information about ANSI escape codes, see:
 * http://en.wikipedia.org/wiki/ANSI_escape_code
 * 
 * This class just filters out the escape codes so that they are not
 * sent out to the underlying OutputStream.  Subclasses should
 * actually perform the ANSI escape behaviors.
 * 
 * @author chirino
 */
public class AnsiOutputStream extends FilterOutputStream {

	public AnsiOutputStream(OutputStream 	os) {
		super(os);
	}

	private  final static int MAX_ESCAPE_SEQUENCE_LENGTH=100;
	private byte buffer[] = new byte[MAX_ESCAPE_SEQUENCE_LENGTH];
	private int pos=0;
	private int startOfValue;
	private final ArrayList<Object> options = new ArrayList<Object>();

	private static final int LOOKING_FOR_FIRST_ESC_CHAR = 0;
	private static final int LOOKING_FOR_SECOND_ESC_CHAR = 1;
	private static final int LOOKING_FOR_NEXT_ARG = 2;
	private static final int LOOKING_FOR_STR_ARG_END = 3;
	private static final int LOOKING_FOR_INT_ARG_END = 4;

	int state = LOOKING_FOR_FIRST_ESC_CHAR;
	
	private static final int FIRST_ESC_CHAR = 27;
	private static final int SECOND_ESC_CHAR = '[';

	// TODO: implement to get perf boost: public void write(byte[] b, int off, int len)
	
	public void write(int data) throws IOException {
		switch( state ) {
		case LOOKING_FOR_FIRST_ESC_CHAR:
			if (data == FIRST_ESC_CHAR) {
				buffer[pos++] = (byte) data;
				state = LOOKING_FOR_SECOND_ESC_CHAR;
			} else {
				out.write(data);
			}
			break;
			
		case LOOKING_FOR_SECOND_ESC_CHAR:
			buffer[pos++] = (byte) data;
			if( data == SECOND_ESC_CHAR ) {
				state = LOOKING_FOR_NEXT_ARG;
			} else {
				buffer[pos++] = (byte) data;
				reset();
			}
			break;
			
		case LOOKING_FOR_NEXT_ARG:
			buffer[pos++] = (byte)data;
			if( '"' == data ) {
				startOfValue=pos-1;
				state = LOOKING_FOR_STR_ARG_END;
			} else if( '0' <= data && data <= '9') {
				startOfValue=pos-1;
				state = LOOKING_FOR_INT_ARG_END;				
			} else if( ';' == data ) {
				options.add(null);
			} else {
				if( processEscapeCommand(options, data) ) {
					pos=0;
				}
				reset();
			}
			break;
			
		case LOOKING_FOR_INT_ARG_END:
			buffer[pos++] = (byte)data;
			if( !('0' <= data && data <= '9') ) {
				Integer value = new Integer(new String(buffer, startOfValue, (pos-1)-startOfValue, "UTF-8"));
				options.add(value);
				if( data == ';' ) {
					state = LOOKING_FOR_NEXT_ARG;
				} else {
					if( data == ';' ) {
						state = LOOKING_FOR_NEXT_ARG;
					} else {
						if( processEscapeCommand(options, data) ) {
							pos=0;
						}
						reset();
					}
				}
			}
			break;
			
		case LOOKING_FOR_STR_ARG_END:
			buffer[pos++] = (byte)data;
			if( '"' != data ) {
				String value = new String(buffer, startOfValue, (pos-1)-startOfValue, "UTF-8");
				options.add(value);
				if( data == ';' ) {
					state = LOOKING_FOR_NEXT_ARG;
				} else {
					if( processEscapeCommand(options, data) ) {
						pos=0;
					}
					reset();
				}
			}
			break;
		}
		
		// Is it just too long?
		if( pos >= buffer.length ) {
			reset();
		}
	}

	private void reset() throws IOException {
		if( pos > 0 ) {
			out.write(buffer, 0, pos);
		}
		pos=0;
		startOfValue=0;
		options.clear();
		state = LOOKING_FOR_FIRST_ESC_CHAR;
	}

	/**
	 * 
	 * @param options
	 * @param command
	 * @return true if the escape command was processed.
	 */
	private boolean processEscapeCommand(ArrayList<Object> options, int command) throws IOException {
		try {
			switch(command) {
			case 'A':
				processCursorUp(optionInt(options, 0, 1));
				return true;
			case 'B':
				processCursorDown(optionInt(options, 0, 1));
				return true;
			case 'C':
				processCursorRight(optionInt(options, 0, 1));
				return true;
			case 'D':
				processCursorLeft(optionInt(options, 0, 1));
				return true;
			case 'E':
				processCursorDownLine(optionInt(options, 0, 1));
				return true;
			case 'F':
				processCursorUpLine(optionInt(options, 0, 1));
			case 'G':
				processCursorToColumn(optionInt(options, 0));
				return true;
			case 'H':
			case 'f':
				processCursorTo(optionInt(options, 0, 1), optionInt(options, 1, 1));
				return true;
			case 'J':
				processEraseScreen(optionInt(options, 0, 0));
				return true;
			case 'K':
				processEraseLine(optionInt(options, 0, 0));
				return true;
			case 'S':
				processScrollUp(optionInt(options, 0, 1));
				return true;
			case 'T':
				processScrollDown(optionInt(options, 0, 1));
				return true;
			case 'm':				
				// Validate all options are ints...
				for (Object next : options) {
					if( next!=null && next.getClass()!=Integer.class) {
						throw new IllegalArgumentException();
					}
				}

				int count=0;
				for (Object next : options) {
					if( next!=null ) {
						count++;
						int value = ((Integer)next).intValue();
						if( 30 <= value && value <= 37 ) {
							processSetForegroundColor(value-30);
						} else if( 40 <= value && value <= 47 ) {
							processSetBackgroundColor(value-40);
						} else {
							switch ( value ) {
							case 39: 
							case 49:
							case 0: processAttributeRest(); break;
							default:
								processSetAttribute(value);
							}
						}
					}
				}
				if( count == 0 ) {
					processAttributeRest();
				}
				return true;
			case 's':
				processSaveCursorPosition();
				return true;
			case 'u':
				processRestoreCursorPosition();
				return true;
				
			default:
				if( 'a' <= command && 'z' <=command ) {
					processUnknownExtension(options, command);
					return true;
				}
				if( 'A' <= command && 'Z' <=command ) {
					processUnknownExtension(options, command);
					return true;
				}
				return false;
			}
		} catch (IllegalArgumentException ignore) {
		}
		return false;
	}

	protected void processRestoreCursorPosition() {
	}
	protected void processSaveCursorPosition() {
	}
	protected void processScrollDown(int optionInt) {
	}
	protected void processScrollUp(int optionInt) {
	}

	protected static final int ERASE_SCREEN_TO_END=0;
	protected static final int ERASE_SCREEN_TO_BEGINING=2;
	protected static final int ERASE_SCREEN=2;
	
	protected void processEraseScreen(int eraseOption) {
	}

	protected static final int ERASE_LINE_TO_END=0;
	protected static final int ERASE_LINE_TO_BEGINING=2;
	protected static final int ERASE_LINE=2;
	
	protected void processEraseLine(int eraseOption) {
	}

	protected static final int ATTRIBUTE_INTENSITY_BOLD 	= 1; // 	Intensity: Bold 	
	protected static final int ATTRIBUTE_INTENSITY_FAINT 	= 2; // 	Intensity; Faint 	not widely supported
	protected static final int ATTRIBUTE_ITALIC 			= 3; // 	Italic; on 	not widely supported. Sometimes treated as inverse.
	protected static final int ATTRIBUTE_UNDERLINE 			= 4; // 	Underline; Single 	
	protected static final int ATTRIBUTE_BLINK_SLOW 		= 5; // 	Blink; Slow 	less than 150 per minute
	protected static final int ATTRIBUTE_BLINK_FAST 		= 6; // 	Blink; Rapid 	MS-DOS ANSI.SYS; 150 per minute or more
	protected static final int ATTRIBUTE_NEGATIVE_ON 		= 7; // 	Image; Negative 	inverse or reverse; swap foreground and background
	protected static final int ATTRIBUTE_CONCEAL_ON 		= 8; // 	Conceal on
	protected static final int ATTRIBUTE_UNDERLINE_DOUBLE 	= 21; // 	Underline; Double 	not widely supported
	protected static final int ATTRIBUTE_INTENSITY_NORMAL 	= 22; // 	Intensity; Normal 	not bold and not faint
	protected static final int ATTRIBUTE_UNDERLINE_OFF 		= 24; // 	Underline; None 	
	protected static final int ATTRIBUTE_BLINK_OFF 			= 25; // 	Blink; off 	
	protected static final int ATTRIBUTE_NEGATIVE_Off 		= 27; // 	Image; Positive 	
	protected static final int ATTRIBUTE_CONCEAL_OFF 		= 28; // 	Reveal 	conceal off

	protected void processSetAttribute(int attribute) throws IOException {
	}

	protected static final int BLACK 	= 0;
	protected static final int RED 		= 1;
	protected static final int GREEN 	= 2;
	protected static final int YELLOW 	= 3;
	protected static final int BLUE 	= 4;
	protected static final int MAGENTA 	= 5;
	protected static final int CYAN 	= 6;
	protected static final int WHITE 	= 7;

	protected void processSetForegroundColor(int color) throws IOException {
	}

	protected void processSetBackgroundColor(int color) throws IOException {
	}

	protected void processAttributeRest() throws IOException {
	}

	protected void processCursorTo(int x, int y) throws IOException {
	}

	protected void processCursorToColumn(int x) throws IOException {
	}

	protected void processCursorUpLine(int count) throws IOException {
	}

	protected void processCursorDownLine(int count) throws IOException {
		// Poor mans impl..
		for(int i=0; i < count; i++) {
			out.write('\n');
		}
	}

	protected void processCursorLeft(int count) throws IOException {
	}

	protected void processCursorRight(int count) throws IOException {
		// Poor mans impl..
		for(int i=0; i < count; i++) {
			out.write(' ');
		}
	}

	protected void processCursorDown(int count) throws IOException {
	}

	protected void processCursorUp(int count) throws IOException {
	}
	
	protected void processUnknownExtension(ArrayList<Object> options, int command) {
	}

	private int optionInt(ArrayList<Object> options, int index) {
		if( options.size() <= index )
			throw new IllegalArgumentException();
		Object value = options.get(index);
		if( value == null )
			throw new IllegalArgumentException();
		if( !value.getClass().equals(Integer.class) )
			throw new IllegalArgumentException();
		return ((Integer)value).intValue();
	}

	private int optionInt(ArrayList<Object> options, int index, int defaultValue) {
		if( options.size() > index ) {
			Object value = options.get(index);
			if( value == null ) {
				return defaultValue;
			}
			return ((Integer)value).intValue();
		}
		return defaultValue;		
	}

}