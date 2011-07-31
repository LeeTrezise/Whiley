package wyil.transforms;

import java.util.*;
import static wyil.util.SyntaxError.syntaxError;
import wyil.*;
import wyil.lang.*;
import wyil.util.*;

/**
 * <p>
 * The point of the coercion check is to check that all convert bytecodes make
 * sense, and are not ambiguous. For example, consider the following code:
 * </p>
 * 
 * <pre>
 * define Rec1 as { real x, int y }
 * define Rec2 as { int x, real y }
 * define uRec1Rec2 as Rec1 | Rec2
 * 
 * int f(uRec1Rec2 r):
 *  if r is Rec1:
 *      return r.y
 *  else:
 *      return r.x
 * 
 * int g():
 *  rec = { x: 1, y: 1}
 *  return f(rec)
 * </pre>
 * 
 * <p>
 * An implicit coercion will be inserted just before the last statement in
 * <code>g()</code>. This will be:
 * </p>
 * 
 * <pre>
 * convert {int x,int y} => {real x,int y}|{int x,real y}
 * </pre>
 * <p>
 * However, this conversion is ambiguous. This is because we could convert the
 * left-hand side to either of the two options in the right-hand side.  
 * </p>
 * 
 * @author djp
 */
public class CoercionCheck implements Transform {
	private final ModuleLoader loader;
	private String filename;

	public CoercionCheck(ModuleLoader loader) {
		this.loader = loader;
	}
	
	public Module apply(Module module) {
		filename = module.filename();
		
		for(Module.Method method : module.methods()) {
			check(method);
		}
		return module;
	}
		
	public void check(Module.Method method) {				
		for (Module.Case c : method.cases()) {
			check(c.body(), method);
		}		
	}
	
	protected void check(Block block,  Module.Method method) {		
		for (int i = 0; i != block.size(); ++i) {
			Block.Entry stmt = block.get(i);
			Code code = stmt.code;
			if (code instanceof Code.Convert) {				
				Code.Convert conv = (Code.Convert) code; 				
				check(conv.from,conv.to,new HashSet<Pair<Type,Type>>(),stmt);
			} 
		}	
	}

	/**
	 * Recursively check that there is no ambiguity in coercing type from into
	 * type to. The visited set is necessary to ensure this process terminates
	 * in the presence of recursive types.
	 * 
	 * @param from
	 * @param to
	 * @param visited - the set of pairs already checked.
	 * @param elem - enclosing syntactic element.
	 */
	protected void check(Type from, Type to, HashSet<Pair<Type, Type>> visited,
			SyntacticElement elem) {
		Pair<Type,Type> p = new Pair<Type,Type>(from,to);
		if(visited.contains(p)) {
			return; // already checked this pair
		} else {
			visited.add(p);
		}
		if(from instanceof Type.Leaf && to instanceof Type.Leaf) {
			// no problem
		} else if(from instanceof Type.Tuple && to instanceof Type.Tuple) {
			Type.Tuple t1 = (Type.Tuple) from;
			Type.Tuple t2 = (Type.Tuple) to;
			List<Type> t1_elements = t1.elements(); 
			List<Type> t2_elements = t2.elements();
			for(int i=0;i!=t2.elements().size();++i) {
				Type e1 = t1_elements.get(i);
				Type e2 = t2_elements.get(i);
				check(e1,e2,visited,elem);
			}
		} else if(from instanceof Type.Process && to instanceof Type.Process) {
			Type.Process t1 = (Type.Process) from;
			Type.Process t2 = (Type.Process) to;
			check(t1.element(),t2.element(),visited,elem);
		} else if(from instanceof Type.Set && to instanceof Type.Set) {
			Type.Set t1 = (Type.Set) from;
			Type.Set t2 = (Type.Set) to;
			check(t1.element(),t2.element(),visited,elem);
		} else if(from instanceof Type.Dictionary && to instanceof Type.Set) {
			Type.Dictionary t1 = (Type.Dictionary) from;
			Type.Set t2 = (Type.Set) to;
			Type.Tuple tup = Type.T_TUPLE(t1.key(),t1.value());
			check(tup,t2.element(),visited,elem);
		} else if(from instanceof Type.List && to instanceof Type.Set) {
			Type.List t1 = (Type.List) from;
			Type.Set t2 = (Type.Set) to;
			check(t1.element(),t2.element(),visited,elem);
		} else if(from instanceof Type.List && to instanceof Type.Dictionary) {
			Type.List t1 = (Type.List) from;
			Type.Dictionary t2 = (Type.Dictionary) to;
			check(t1.element(),t2.value(),visited,elem);
		} else if(from instanceof Type.List && to instanceof Type.List) {
			Type.List t1 = (Type.List) from;
			Type.List t2 = (Type.List) to;
			check(t1.element(),t2.element(),visited,elem);
		} else if(from instanceof Type.Record && to instanceof Type.Record) {
			Type.Record t1 = (Type.Record) from;
			Type.Record t2 = (Type.Record) to;
			HashMap<String,Type> t1_elements = t1.fields(); 
			HashMap<String,Type> t2_elements = t2.fields();
			ArrayList<String> fields = new ArrayList<String>(t2.keys());
			for(String s : fields) {
				Type e1 = t1_elements.get(s);
				Type e2 = t2_elements.get(s);
				check(e1,e2,visited,elem);
			}			
		} else if(from instanceof Type.Fun && to instanceof Type.Fun) {
			Type.Fun t1 = (Type.Fun) from;
			Type.Fun t2 = (Type.Fun) to;
			List<Type> t1_elements = t1.params(); 
			List<Type> t2_elements = t2.params();			
			for(int i=0;i!=t1_elements.size();++i) {
				Type e1 = t1_elements.get(i);
				Type e2 = t2_elements.get(i);
				check(e1,e2,visited,elem);
			}			
			check(t1.ret(),t2.ret(),visited,elem);
		} else if(from instanceof Type.Union) {
			Type.Union t1 = (Type.Union) from; 
			for(Type b : t1.bounds()) {
				check(b,to,visited,elem);
			}
		} else if(to instanceof Type.Union) {			
			Type.Union t2 = (Type.Union) to;			
			
			// First, check for identical type (i.e. no coercion necessary)
			
			for(Type b : t2.bounds()) {
				if(Type.isomorphic(from, b)) {
					// no problem
					return;
				}
			}
			
			// Second, check for single non-coercive match
			Type match = null;			
			
			for(Type b : t2.bounds()) {
				if(Type.isSubtype(b,from)) {
					if(match != null) {
						// found ambiguity
						syntaxError("ambiguous coercion (" + from + " => "
								+ to, filename, elem);
					} else {
						check(from,b,visited,elem);
						match = b;						
					}
				}
			}
			
			if(match != null) {
				// ok, we have a hit on a non-coercive subtype.
				return;
			}
			
			// Third, test for single coercive match
			
			for(Type b : t2.bounds()) {
				if(Type.isCoerciveSubtype(b,from)) {
					if(match != null) {
						// found ambiguity
						syntaxError("ambiguous coercion (" + from + " => "
								+ to, filename, elem);
					} else {
						check(from,b,visited,elem);
						match = b;						
					}
				}
			}
		}		
	}
}