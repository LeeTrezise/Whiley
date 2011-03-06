package wyil.jvm.rt;

import java.util.*;

public class Util {

	private static final boolean debug = false;
	
	static { 
		if(debug) {
			Runtime.getRuntime().addShutdownHook(new Thread(){
				public void run() {
					System.err.println("===========================================");
					System.err.println("CLONING STATS");
					System.err.println("===========================================");
					System.err.println("list clones: " + nlist_clones);
					System.err.println("set clones: " + nset_clones);
					System.err.println("record clones: " + nrecord_clones);
				}
			});
		}
	}	
	
	private static int nlist_clones = 0;
	private static int nset_clones = 0;
	private static int nrecord_clones = 0;
	
	public static Object clone(Object o) {
		if(o instanceof BigRational || o instanceof Boolean || o == null) {
			return o;
		} else if(o instanceof ArrayList) {
			return list_clone((ArrayList)o);
		} else if(o instanceof WhileySet) {
			return set_clone((WhileySet)o);
		} else {
			return record_clone((WhileyRecord)o);
		} 
	}
	
	public static ArrayList list_clone(ArrayList in) {
		nlist_clones++;
		ArrayList l = new ArrayList();
		for(Object o : in) {
			l.add(clone(o));
		}		
		return l;
	}
	
	public static WhileySet set_clone(WhileySet in) {
		nset_clones++;
		WhileySet l = new WhileySet();
		for(Object o : in) {
			l.add(clone(o));
		}
		return l;
	}
	
	public static WhileyRecord record_clone(WhileyRecord in) {
		nrecord_clones++;
		WhileyRecord l = new WhileyRecord();
		for(Map.Entry<String,Object> o : in.entrySet()) {
			l.put(o.getKey(),clone(o.getValue()));
		}
		return l;
	}
	

	/**
	 * Compute a sublist of a list.
	 * @param start
	 * @param end
	 * @return
	 */
	public static ArrayList sublist(ArrayList list, BigRational start, BigRational end) {
		int st = start.intValue();
		int en = end.intValue();
		ArrayList r = new ArrayList();
		for(int i=st;i!=en;++i) {
			r.add(list.get(i));
		}
		return r;
	}
	
	/**
	 * Append two lists together
	 * @param rhs
	 * @return
	 */
	public static ArrayList append(ArrayList lhs, ArrayList rhs) {
		ArrayList r = new ArrayList(lhs);
		r.addAll(rhs);
		return r;
	}
	
	/**
	 * Generate an integer range from start and end values.
	 * @param start
	 * @param end
	 * @return
	 */
	public static ArrayList range(BigRational start, BigRational end) {
		ArrayList ret = new ArrayList();
		
		// FIXME: seems ludicrously inefficient!
		BigRational dir = BigRational.valueOf(end.compareTo(start));
		
		while(!start.equals(end)) {
			ret.add(start);
			start = start.add(dir);
		}
		
		return ret;
	}	
	
	/**
	 * The following method is used by the main launcher to convert from Java's
	 * main(String[] args) into whiley's main([string] args) format.
	 * 
	 * @param args
	 * @return
	 */
	public static ArrayList fromStringList(String[] args) {
		ArrayList r = new ArrayList();
		for(String s : args) {
			r.add(fromString(s));
		}
		return r;
	}
	
	/**
	 * Generate a Whiley list from a Java String. 
	 * @param s
	 * @return
	 */
	public static ArrayList fromString(String s) {
		ArrayList r = new ArrayList();
		for(int i=0;i!=s.length();++i) {
			int c = s.charAt(i);
			r.add(BigRational.valueOf(c));
		}
		return r;
	}

	/**
	 * Convert a Whiley list into a Java String
	 * @param list
	 * @return
	 */
	public static String toString(ArrayList list) {
		String r = "";
		for(Object o : list) {
			if(o instanceof BigRational) {
				int v = ((BigRational)o).intValue();
				r += (char) v;
			} else {
				throw new RuntimeException("Invalid Whiley List");
			}
		}
		return r;
	}

	/**
	 * This method is used for the special case when the left-hand side of an
	 * equality operation may be null.
	 * 
	 * @param o1
	 * @param o2
	 * @return
	 */
	public static boolean equals(Object o1, Object o2) {
		return (o1 != null && o1.equals(o2)) || (o1 == o2);
	}
	
	/**
	 * The following method is used for printing debug output arising from debug
	 * statements.
	 * 
	 * @param list
	 */
	public static void debug(ArrayList list) {
		for(Object o : list) {
			if(o instanceof BigRational) {
				BigRational bi = (BigRational) o;
				System.out.print((char)bi.intValue());
			}
		}
		System.out.println("");
	}
}