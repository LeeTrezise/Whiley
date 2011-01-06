// This file is part of the Whiley-to-Java Compiler (wyjc).
//
// The Whiley-to-Java Compiler is free software; you can redistribute 
// it and/or modify it under the terms of the GNU General Public 
// License as published by the Free Software Foundation; either 
// version 3 of the License, or (at your option) any later version.
//
// The Whiley-to-Java Compiler is distributed in the hope that it 
// will be useful, but WITHOUT ANY WARRANTY; without even the 
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
// PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Whiley-to-Java Compiler. If not, see 
// <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyil.io;

import java.math.BigInteger;
import java.util.*;

import wyil.jvm.attributes.WhileyDefine;
import wyil.jvm.attributes.WhileyVersion;
import wyil.jvm.rt.BigRational;
import wyil.*;
import static wyil.util.SyntaxError.*;
import wyil.util.Pair;
import wyil.util.SyntaxError;
import wyil.lang.*;
import wyil.lang.CExpr.LVal;
import wyil.lang.Type;
import wyil.lang.Code;
import wyjvm.lang.*;
import wyjvm.util.DeadCodeElimination;
import wyjx.jvm.attributes.*;
import static wyjvm.lang.JvmTypes.*;

/**
 * The purpose of the class file builder is to construct a jvm class file from a
 * given WhileyFile.
 * 
 * @author djp
 */
public class ClassFileBuilder {
	protected int CLASS_VERSION = 49;
	protected int WHILEY_MINOR_VERSION;
	protected int WHILEY_MAJOR_VERSION;
	protected ModuleLoader loader;	
	protected String filename;
	
	public ClassFileBuilder(ModuleLoader loader, int whileyMajorVersion, int whileyMinorVersion) {
		this.loader = loader;
		this.WHILEY_MINOR_VERSION = whileyMinorVersion;
		this.WHILEY_MAJOR_VERSION = whileyMajorVersion;
	}

	public ClassFile build(Module module) {
		JvmType.Clazz type = new JvmType.Clazz(module.id().pkg().toString(),
				module.id().module().toString());
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.ACC_PUBLIC);
		modifiers.add(Modifier.ACC_FINAL);
		ClassFile cf = new ClassFile(49, type, JAVA_LANG_OBJECT,
				new ArrayList<JvmType.Clazz>(), modifiers);
	
		this.filename = module.filename();
		
		boolean addMainLauncher = false;		
		
		for(Module.ConstDef d : module.constants()) {						
			WhileyDefine wd = new WhileyDefine(d.name(),d.constant());
			cf.attributes().add(wd);
		}
		for(Module.TypeDef td : module.types()) {			
			Type t = td.type();			
			WhileyDefine wd = new WhileyDefine(td.name(),t);
			cf.attributes().add(wd);
		}
		for(Module.Method method : module.methods()) {				
			if(method.name().equals("main")) { 
				addMainLauncher = true;
			}			
			cf.methods().addAll(build(method));			
		}		
		
		if(addMainLauncher) {
			cf.methods().add(buildMainLauncher(type));
		}
		
		cf.attributes().add(
				new WhileyVersion(WHILEY_MAJOR_VERSION, WHILEY_MINOR_VERSION));
		
		return cf;
	}	
		
	public ClassFile.Method buildMainLauncher(JvmType.Clazz owner) {
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.ACC_PUBLIC);
		modifiers.add(Modifier.ACC_STATIC);		
		modifiers.add(Modifier.ACC_SYNTHETIC);		
		JvmType.Function ft1 = new JvmType.Function(T_VOID, new JvmType.Array(JAVA_LANG_STRING));		
		ClassFile.Method cm = new ClassFile.Method("main",ft1,modifiers);
		JvmType.Array strArr = new JvmType.Array(JAVA_LANG_STRING);
		ArrayList<Bytecode> codes = new ArrayList<Bytecode>();
		ft1 = new JvmType.Function(WHILEYPROCESS);
		codes.add(new Bytecode.Invoke(WHILEYPROCESS,"systemProcess",ft1,Bytecode.STATIC));
		codes.add(new Bytecode.Load(0,strArr));
		JvmType.Function ft2 = new JvmType.Function(WHILEYLIST,
				new JvmType.Array(JAVA_LANG_STRING));
		codes.add(new Bytecode.Invoke(WHILEYLIST,"fromStringList",ft2,Bytecode.STATIC));
		JvmType.Function ft3 = new JvmType.Function(T_VOID, WHILEYPROCESS, WHILEYLIST);
		
		codes
				.add(new Bytecode.Invoke(
						owner,
						"main$Nwhiley.lang.System;System;P(out:Nwhiley.lang.System;SystemOutWriter;P?rest:?)$V[[I]]",
						// "main$Nwhiley.lang.System;System;P?$V[Nwhiley.lang.String;string;[I]]",
						ft3, Bytecode.STATIC));
		codes.add(new Bytecode.Return(null));
		
		wyjvm.attributes.Code code = new wyjvm.attributes.Code(codes,
				new ArrayList(), cm);
		cm.attributes().add(code);
		
		return cm;	
	}
	
	public List<ClassFile.Method> build(Module.Method method) {
		ArrayList<ClassFile.Method> methods = new ArrayList<ClassFile.Method>();
		int num = 1;
		for(Module.Case c : method.cases()) {
			methods.add(build(num++,c,method));
		}
		return methods;
	}
	
	public ClassFile.Method build(int caseNum, Module.Case mcase,
			Module.Method method) {		
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		if(method.isPublic()) {
			modifiers.add(Modifier.ACC_PUBLIC);
		}
		modifiers.add(Modifier.ACC_STATIC);		
		JvmType.Function ft = (JvmType.Function) convertType(method.type());		
		String name = nameMangle(method.name(),method.type());
		if(method.cases().size() > 1) {
			name = name + "$" + caseNum;
		}
		ClassFile.Method cm = new ClassFile.Method(name,ft,modifiers);		
		ArrayList<Bytecode> codes = translate(mcase, method);
		wyjvm.attributes.Code code = new wyjvm.attributes.Code(codes,new ArrayList(),cm);
		cm.attributes().add(code);				

		// Build up the binding we'll use for pre and post-conditions
		List<String> params = mcase.parameterNames();
		List<Type> paramTypes = method.type().params;
		HashMap<String,CExpr> binding = new HashMap<String,CExpr>();
		
		for(int i=0;i!=params.size();++i) {
			String n = params.get(i);
			binding.put(n,CExpr.VAR(paramTypes.get(i),"p" + i));
		}
		
		return cm;
	}
	
	public ArrayList<Bytecode> translate(Module.Case mcase, Module.Method method) {
		ArrayList<Bytecode> bytecodes = new ArrayList<Bytecode>();		
		HashMap<String, Integer> slots = new HashMap<String, Integer>();		
		
		HashSet<String> uses = Block.usedVariables(mcase.body());
			
		int slot = 0;
		if(method.type().receiver != null) {
			slots.put("this",slot++);
			uses.remove("this");
		}
				
		List<Type> paramTypes = method.type().params;
		List<String> paramNames = mcase.parameterNames();
		for(int i=0;i!=paramTypes.size();++i) {
			String name = paramNames.get(i);			
			slots.put(name,slot++);			
			uses.remove(name);
		}
		
		for(String v : uses) {
			slots.put(v,slot++);						
		}				
		
		translate(mcase.body(),slots,bytecodes,method);		
		
		return bytecodes;
	}
	
	public void translate(Block blk, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes, Module.Method method) {
		for (Stmt s : blk) {
			translate(s, slots, bytecodes, method);
		}
	}
	
	public void translate(Stmt stmt, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes, Module.Method method) {
		try {
			Code c = stmt.code;
			if (c instanceof Code.Assign) {
				translate((Code.Assign) c, slots, bytecodes);
			} else if (c instanceof Code.Return) {
				translate((Code.Return) c, slots, bytecodes, method);
			} else if (c instanceof Code.Goto) {
				translate((Code.Goto) c, slots, bytecodes);
			} else if (c instanceof Code.IfGoto) {
				translate((Code.IfGoto) c, stmt, slots, bytecodes);
			} else if (c instanceof Code.Forall) {
				translate((Code.Forall) c, slots, bytecodes);
			} else if (c instanceof Code.Loop) {
				translate((Code.Loop) c, slots, bytecodes);
			} else if (c instanceof Code.LoopEnd) {
				translate((Code.LoopEnd) c, slots, bytecodes);
			} else if (c instanceof Code.Label) {
				translate((Code.Label) c, slots, bytecodes);
			} else if (c instanceof Code.Debug) {
				translate((Code.Debug) c, slots, bytecodes);
			} else if (c instanceof Code.Fail) {
				translate((Code.Fail) c, slots, bytecodes);
			} else if (c instanceof Code.ExternJvm) {
				translate((Code.ExternJvm) c, slots, bytecodes);
			}
		} catch (SyntaxError ex) {
			throw ex;
		} catch (Exception ex) {		
			syntaxError("internal failure", filename, stmt, ex);
		}
	}
	
	public void translate(Code.Assign c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		
		if(c.lhs != null) {
			makePreAssignment(c.lhs,slots,bytecodes);		
			// Translate right-hand side
			translate(c.rhs,slots,bytecodes);			
			// Write assignment
			makePostAssignment(c.lhs,slots,bytecodes);
		} else {
			translate(c.rhs,slots,bytecodes);
			Type ret_t = c.rhs.type();
			if (ret_t != Type.T_VOID) {
				// need to drop the rhs value, since it's not used.
				bytecodes.add(new Bytecode.Pop(convertType(ret_t)));
			}
		}
	}

	public void translate(Code.Return c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes, Module.Method method) {
		
		if (c.rhs != null) {
			translate(c.rhs, slots, bytecodes);
			Type ret_t = method.type().ret;
			convert(ret_t,c.rhs.type(),slots,bytecodes);			
			bytecodes.add(new Bytecode.Return(convertType(ret_t)));
		} else {		
			bytecodes.add(new Bytecode.Return(null));
		}
	}

	
	public void translate(Code.IfGoto c, Stmt stmt, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {	
		
		Type lub = Type.leastUpperBound(c.lhs.type(),c.rhs.type());
		
		if (c.op == Code.COP.SUBTYPEEQ || c.op == Code.COP.NSUBTYPEEQ) {
			// special case: don't translate rhs
			translate(c.lhs, slots, bytecodes);
		} else if(c.op == Code.COP.ELEMOF) {
			// special case: do things backwards
			translate(c.rhs,slots,bytecodes);
			translate(c.lhs,slots,bytecodes);
			// FIXME: bug here related to conversion of element type
		} else {
			translate(c.lhs,slots,bytecodes);
			
			convert(lub,c.lhs.type(),slots,bytecodes);
			
			translate(c.rhs,slots,bytecodes);
			
			convert(lub,c.rhs.type(),slots,bytecodes);
		}		
		
		JvmType type = convertType(lub);
		if(lub == Type.T_BOOL) {
			// boolean is a special case, since it is not implemented as an
			// object on the JVM stack. Therefore, we need to use the "if_cmp"
			// bytecode, rather than calling .equals() and using "if" bytecode.
			switch(c.op) {
			case EQ:
				bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.EQ, type, c.target));
				break;			
			case NEQ:
				bytecodes.add(new Bytecode.IfCmp(Bytecode.IfCmp.NE, type, c.target));				
				break;			
			}
		} else {
			// Non-boolean case. Just use the Object.equals() method, followed
			// by "if" bytecode.			
			int op;
			switch(c.op) {
			case EQ:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "equals", ftype,
						Bytecode.VIRTUAL));			
				op = Bytecode.If.NE;
				break;
			}
			case NEQ:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "equals", ftype,
						Bytecode.VIRTUAL));
				op = Bytecode.If.EQ;
				break;
			}
			case LT:
			{			
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type, "compareTo", ftype,
						Bytecode.VIRTUAL));
				op = Bytecode.If.LT;			
				break;
			}
			case LTEQ:
			{			
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));
				op = Bytecode.If.LE;
				break;
			}
			case GT:
			{		
				JvmType.Function ftype = new JvmType.Function(T_INT, type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));
				op = Bytecode.If.GT;
				break;
			}
			case GTEQ:
			{		
				JvmType.Function ftype = new JvmType.Function(T_INT,type);
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type,
						"compareTo", ftype, Bytecode.VIRTUAL));			
				op = Bytecode.If.GE;
				break;
			}
			case SUBSETEQ:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,WHILEYSET);
				bytecodes.add(new Bytecode.Invoke(WHILEYSET, "subsetEq", ftype,
						Bytecode.VIRTUAL));
				op = Bytecode.If.NE;
				break;
			}
			case SUBSET:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,WHILEYSET);
				bytecodes.add(new Bytecode.Invoke(WHILEYSET, "subset", ftype,
						Bytecode.VIRTUAL));
				op = Bytecode.If.NE;
				break;
			}
			case ELEMOF:
			{
				JvmType.Function ftype = new JvmType.Function(T_BOOL,
						JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "contains",
						ftype, Bytecode.INTERFACE));
				op = Bytecode.If.NE;
				break;
			}			
			case SUBTYPEEQ:
			{				
				Type rhs_t = ((Value.TypeConst)c.rhs).type;
				translateTypeTest(c.target,c.lhs, rhs_t, stmt, slots, bytecodes);				
				return;				
			}
			case NSUBTYPEEQ:
			{	
				String trueLabel = freshLabel();
				Type rhs_t = ((Value.TypeConst)c.rhs).type;
				translateTypeTest(trueLabel, c.lhs, rhs_t, stmt, slots, bytecodes);
				bytecodes.add(new Bytecode.Goto(c.target));
				bytecodes.add(new Bytecode.Label(trueLabel));				
				return;
			}
			default:
				syntaxError("unknown if condition encountered",filename,stmt);
				return;
			}
			
			// do the jump
			bytecodes.add(new Bytecode.If(op, c.target));
		}
	}
	
	protected void translateTypeTest(String trueTarget, CExpr src, Type test,
			Stmt stmt, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {				
		
		// This method (including the helper) is pretty screwed up. It needs a
		// serious rethink to catch all cases, and to be efficient.
		
		Type src_t = src.type();		
		String exitLabel = freshLabel();
		String trueLabel = freshLabel();
		translateTypeTest(trueLabel, src.type(), test, stmt, bytecodes);
		
		if(src instanceof CExpr.LVar) {					
			// This covers the limited form of type inference currently
			// supported in Whiley. Essentially, it works only for the
			// case where we are testing against a variable.
			CExpr.LVar v = (CExpr.LVar) src;				
			Type gdiff = Type.greatestDifference(src_t,test);
			translate(src,slots,bytecodes);
			addReadConversion(gdiff,bytecodes);
			JvmType rhs_jt = convertType(gdiff);					
			bytecodes.add(new Bytecode.Store(slots.get(v.name()),rhs_jt));					
		}
		
		bytecodes.add(new Bytecode.Goto(exitLabel));
		bytecodes.add(new Bytecode.Label(trueLabel));
		
		if(src instanceof CExpr.LVar) {					
			// This covers the limited form of type inference currently
			// supported in Whiley. Essentially, it works only for the
			// case where we are testing against a variable.
			CExpr.LVar v = (CExpr.LVar) src;
			Type glb = Type.greatestLowerBound(src_t,test);		
			translate(src,slots,bytecodes);
			addReadConversion(glb,bytecodes);
			JvmType rhs_jt = convertType(glb);					
			bytecodes.add(new Bytecode.Store(slots.get(v.name()),rhs_jt));					
		}
		
		bytecodes.add(new Bytecode.Goto(trueTarget));
		bytecodes.add(new Bytecode.Label(exitLabel));
	}
	
	// The purpose of this method is to translate a type test. We're testing to
	// see whether what's on the top of the stack (the value) is a subtype of
	// the type being tested. The difference between value's static type and
	// test type help to reduce the scope of the test. For example, if the
	// static type is int|[int] and the test type is int, then all we need is to
	// perform an instanceof BigInteger. Other situations are trickier. For
	// example, testing a static type [int]|[bool] against type [int] is harder,
	// since both are actually instances of java.util.List. 
	protected void translateTypeTest(String trueTarget, Type src, Type test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {		
		
		// First, determine the intersection of the actual type and the type
		// we're testing for.  This is really an optimisation.
		test = Type.greatestLowerBound(src,test);					
		
		if(test == Type.T_VOID) {
			// in this case, we must fail.
			bytecodes.add(new Bytecode.Pop(convertType(src)));			
		} else if(Type.isSubtype(test, src)) {			
			// in this case, we must succeed.
			bytecodes.add(new Bytecode.Pop(convertType(src)));
			bytecodes.add(new Bytecode.Goto(trueTarget));
		} else if (test instanceof Type.Null) {
			// Easy case		
			bytecodes.add(new Bytecode.If(Bytecode.If.NULL, trueTarget));
		} else if(test instanceof Type.Int) {
			translateTypeTest(trueTarget, src, (Type.Int) test,
					stmt, bytecodes);
		} else if(test instanceof Type.Real) {
			translateTypeTest(trueTarget, src, (Type.Real) test,
					stmt, bytecodes);			
		} else if(test instanceof Type.List) {
			translateTypeTest(trueTarget, src, (Type.List) test,
					stmt, bytecodes);			
		} else if(test instanceof Type.Set) {
			translateTypeTest(trueTarget, src, (Type.Set) test, stmt, bytecodes);			
		} else if(test instanceof Type.Record) {				
			translateTypeTest(trueTarget, src, (Type.Record) test, stmt,
					bytecodes);			
		} else if(test instanceof Type.Union){
			translateTypeTest(trueTarget, src, (Type.Union) test, stmt,
					bytecodes);			
		} else if(test instanceof Type.Recursive) {
			translateTypeTest(trueTarget, src, (Type.Recursive) test, stmt,
					bytecodes);				
		}				
	}

	/**
	 * Check that a value of the given src type is an integer. The main
	 * difficulty here, is that it may not be enough to just test whether the
	 * value is a BigInteger. For example, if src has type real then we have a
	 * BigRational, and we must call the isInteger() method instead.
	 * 
	 * @param trueTarget --- target branch if test succeeds
	 * @param src --- type of expression being tested
	 * @param test --- type of test
	 * @param stmt --- stmt containing test (useful for line number info)
	 * @param bytecodes --- list of bytecodes (to which test is appended) 	 
	 */
	protected void translateTypeTest(String trueTarget, Type src, Type.Int test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {
		
		// NOTE: on entry we know that src cannot be a Type.Int, since this case
		// would have been already caught.
		
		// ======================================================================
		// Perform the instanceof BigRational (if necessary)  
		// ======================================================================
				
		String falseTarget = freshLabel();

		if (!Type.isSubtype(Type.T_REAL, src)) {			
			String nextTarget = freshLabel();			
			bytecodes.add(new Bytecode.Dup(convertType(src)));
			bytecodes.add(new Bytecode.InstanceOf(BIG_RATIONAL));
			bytecodes.add(new Bytecode.If(Bytecode.If.NE, nextTarget));
			bytecodes.add(new Bytecode.Pop(convertType(src)));
			bytecodes.add(new Bytecode.Goto(falseTarget));
			bytecodes.add(new Bytecode.Label(nextTarget));
			bytecodes.add(new Bytecode.CheckCast(BIG_RATIONAL));
		}

		// =================================================================
		// Check whether our BigRational is an integer	
		// =================================================================

		// FIXME: there's a bug here, since once we know isInteger then we
		// obviously have to extract a BigInteger object at some point.

		JvmType.Function fun_t = new JvmType.Function(JvmTypes.T_BOOL);
		bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "isInteger", fun_t , Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));			
		bytecodes.add(new Bytecode.Label(falseTarget));				
	}
	
	/**
	 * Check that a value of the given src type is an real. 
	 * 
	 * @param trueTarget --- target branch if test succeeds
	 * @param src --- type of expression being tested
	 * @param test --- type of test
	 * @param stmt --- stmt containing test (useful for line number info)
	 * @param bytecodes --- list of bytecodes (to which test is appended) 	 
	 */
	protected void translateTypeTest(String trueTarget, Type src, Type.Real test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {
		
		// NOTE: on entry we know that src cannot be a Type.Real, since this case
		// would have been already caught.
									
		bytecodes.add(new Bytecode.InstanceOf(BIG_RATIONAL));
		bytecodes.add(new Bytecode.If(Bytecode.If.NE, trueTarget));				
	}
	
	/**
	 * Check that a value of the given src type matches the list type given by
	 * test. If src is not a list, then we must begin by performing an
	 * instanceof WHILEYLIST. If this is true, then we may need to further
	 * distinguish the elements of the list. For example, testing [real] ~=
	 * [int] requires iterating the elements of the list to check that they are
	 * all indeed ints.
	 * 
	 * @param trueTarget --- target branch if test succeeds
	 * @param src --- type of expression being tested
	 * @param test --- type of test
	 * @param stmt --- stmt containing test (useful for line number info)
	 * @param bytecodes --- list of bytecodes (to which test is appended) 
	 */
	protected void translateTypeTest(String trueTarget, Type src, Type.List test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {								
		
		// ======================================================================
		// First, perform an instanceof test (if necessary) 
		// ======================================================================
		
		Type.List nsrc;
		String falseTarget = freshLabel();
		
		if(src instanceof Type.List) {
			// We already know the value is a list, so we don't need to perform
			// an instanceof test.		
			nsrc = (Type.List) src;
		} else {			
			bytecodes.add(new Bytecode.Dup(convertType(src)));		
			bytecodes.add(new Bytecode.InstanceOf(WHILEYLIST));
			bytecodes.add(new Bytecode.If(Bytecode.If.EQ, falseTarget));
			addCheckCast(WHILEYLIST,bytecodes);				
			
			// FIXME: this is a bug as we're guaranteed to have a list type
			// here. For example, int|[int]|[real] & [*] ==> [int]|[real] (by S-UNION2)
			nsrc = (Type.List) Type.greatestLowerBound(src, Type.T_LIST(Type.T_ANY));						
			
			if(Type.isSubtype(test,nsrc)) {				
				// Getting here indicates that the instanceof test was
				// sufficient to be certain that the type test succeeds.			
				bytecodes.add(new Bytecode.Pop(WHILEYLIST));
				bytecodes.add(new Bytecode.Goto(trueTarget));				
				bytecodes.add(new Bytecode.Label(falseTarget));
				bytecodes.add(new Bytecode.Pop(WHILEYLIST));
				return;
			}
		}
						
		// ======================================================================
		// Second, check empty list case (as this always passes) 
		// ======================================================================		
		
		String nextTarget = freshLabel();
		bytecodes.add(new Bytecode.Dup(WHILEYLIST));
		JvmType.Function fun_t = new JvmType.Function(JvmTypes.T_INT);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "size", fun_t , Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.If(Bytecode.If.NE, nextTarget));
		bytecodes.add(new Bytecode.Pop(WHILEYLIST));
		bytecodes.add(new Bytecode.Goto(trueTarget));
		bytecodes.add(new Bytecode.Label(nextTarget));
		
		// ======================================================================
		// Third, check elements of list (tricky)
		// ======================================================================		
						
		fun_t = new JvmType.Function(JAVA_LANG_OBJECT,JvmTypes.T_INT);
		bytecodes.add(new Bytecode.LoadConst(new Integer(0)));
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "get", fun_t , Bytecode.VIRTUAL));			
		translateTypeTest(trueTarget,nsrc.element,test.element(),stmt,bytecodes);
		
		// Add the false label for the case when the original instanceof test fails
		bytecodes.add(new Bytecode.Label(falseTarget));
		bytecodes.add(new Bytecode.Pop(WHILEYLIST));
	}

	/**
	 * Check that a value of the given src type matches the set type given by
	 * test. If src is not a set, then we must begin by performing an
	 * instanceof WHILEYSET. If this is true, then we may need to further
	 * distinguish the elements of the list. For example, testing {real} ~=
	 * {int} requires iterating the elements of the list to check that they are
	 * all indeed ints.
	 * 
	 * @param trueTarget --- target branch if test succeeds
	 * @param src --- type of expression being tested
	 * @param test --- type of test
	 * @param stmt --- stmt containing test (useful for line number info)
	 * @param bytecodes --- list of bytecodes (to which test is appended) 
	 */
	protected void translateTypeTest(String trueTarget, Type src, Type.Set test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {
		
		// ======================================================================
		// First, perform an instanceof test (if necessary) 
		// ======================================================================
		
		Type.Set nsrc;
		String falseTarget = freshLabel();
		
		if(src instanceof Type.List) {
			// We already know the value is a list, so we don't need to perform
			// an instanceof test.		
			nsrc = (Type.Set) src;
		} else {			
			bytecodes.add(new Bytecode.Dup(convertType(src)));		
			bytecodes.add(new Bytecode.InstanceOf(WHILEYSET));
			bytecodes.add(new Bytecode.If(Bytecode.If.EQ, falseTarget));
			addCheckCast(WHILEYSET,bytecodes);				
			
			// FIXME: this is a bug as we're guaranteed to have a set type
			// here. For example, int|{int}|{real} & [*] ==> {int}|{real} (by S-UNION2)
			nsrc = (Type.Set) Type.greatestLowerBound(src, Type.T_SET(Type.T_ANY));
			
			if(Type.isSubtype(test,nsrc)) {
				// Getting here indicates that the instanceof test was
				// sufficient to be certain that the type test succeeds.			
				bytecodes.add(new Bytecode.Pop(WHILEYSET));
				bytecodes.add(new Bytecode.Goto(trueTarget));				
				bytecodes.add(new Bytecode.Label(falseTarget));
				bytecodes.add(new Bytecode.Pop(WHILEYSET));
				return;
			}
		}
						
		// ======================================================================
		// Second, check empty set case (as this always passes) 
		// ======================================================================		
		
		String nextTarget = freshLabel();
		bytecodes.add(new Bytecode.Dup(WHILEYSET));
		JvmType.Function fun_t = new JvmType.Function(JvmTypes.T_INT);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "size", fun_t , Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.If(Bytecode.If.NE, nextTarget));
		bytecodes.add(new Bytecode.Pop(WHILEYSET));
		bytecodes.add(new Bytecode.Goto(trueTarget));
		bytecodes.add(new Bytecode.Label(nextTarget));
		
		// ======================================================================
		// Third, check elements of list (tricky)
		// ======================================================================		
						
		fun_t = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "iterator", fun_t , Bytecode.VIRTUAL));			
		fun_t = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next", fun_t , Bytecode.INTERFACE));			
		translateTypeTest(trueTarget,nsrc.element,test.element(),stmt,bytecodes);
		
		// Add the false label for the case when the original instanceof test fails
		bytecodes.add(new Bytecode.Label(falseTarget));
		bytecodes.add(new Bytecode.Pop(WHILEYSET));
	}

	/**
	 * <p>
	 * Check that a value of the given src type matches the record type given by
	 * test. If src is not a record, then we must begin by performing an
	 * instanceof WHILEYRECORD. If this is true, then we may need to further and
	 * to distinguish which fields it actually has. We optimise this by making
	 * the least number of field checks possible. For example, consider this
	 * type:
	 * </p>
	 * 
	 * <pre>
	 * define RType as {int x,int y}|{int x, int z}|int
	 * 
	 * int f(RType r):
	 *     if e &tilde;= {int x, int y}
	 *         return 1
	 *     else:
	 *         return 2
	 * </pre>
	 * <p>
	 * In this case, <code>r</code> will have type <code>Object</code> on entry.
	 * Thus, we must perform an <code>instanceof</code>
	 * <code>WhileyRecord</code> check. Furthermore, in the true case, we must
	 * establish whether or not we have a field <code>y</code> (but we don't
	 * need to test for field <code>x</code>).
	 * </p>
	 * 
	 * @param trueTarget
	 *            --- target branch if test succeeds
	 * @param src
	 *            --- type of expression being tested
	 * @param test
	 *            --- type of test
	 * @param stmt
	 *            --- stmt containing test (useful for line number info)
	 * @param bytecodes
	 *            --- list of bytecodes (to which test is appended)
	 */
	protected void translateTypeTest(String trueTarget, Type src,
			Type.Record test, Stmt stmt, ArrayList<Bytecode> bytecodes) {
				
		JvmType.Function fun_t = new JvmType.Function(JvmTypes.JAVA_LANG_OBJECT,JvmTypes.JAVA_LANG_OBJECT);
		
		// ======================================================================
		// First, perform an instanceof test (if necessary) 
		// ======================================================================
				
		String falseTarget = freshLabel();
		
		if(!(src instanceof Type.Record)) {
			
			if(Type.effectiveRecordType(src) == null) {					
				// not guaranteed to have a record here, so ensure we do.
				bytecodes.add(new Bytecode.Dup(convertType(src)));		
				bytecodes.add(new Bytecode.InstanceOf(WHILEYRECORD));
				bytecodes.add(new Bytecode.If(Bytecode.If.EQ, falseTarget));			
				addCheckCast(WHILEYRECORD,bytecodes);	

				// Narrow the type down to a record (which we now know is true)			
				src = narrowRecordType(src);							
				
				if(Type.isSubtype(test,src)) {				
					// Getting here indicates that the instanceof test was
					// sufficient to be certain that the type test succeeds.			
					bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
					bytecodes.add(new Bytecode.Goto(trueTarget));
					bytecodes.add(new Bytecode.Label(falseTarget));
					bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
					return;
				}								
			}
			
			// ======================================================================
			// Second, determine if correct fields present  
			// ======================================================================
			
			Set<String> fields = identifyDistinguishingFields(src,test.types.keySet()); 			
			
			for(String f : fields) {
				bytecodes.add(new Bytecode.Dup(WHILEYRECORD));
				bytecodes.add(new Bytecode.LoadConst(f));				
				bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "get", fun_t , Bytecode.VIRTUAL));
				bytecodes.add(new Bytecode.If(Bytecode.If.NULL,falseTarget));
			}
			
			src = narrowRecordType(src,test.types.keySet());
			
			if(Type.isSubtype(test,src)) {
				// Getting here indicates that distinguishing fields test was
				// sufficient to be certain that the type test succeeds.			
				bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
				bytecodes.add(new Bytecode.Goto(trueTarget));				
				bytecodes.add(new Bytecode.Label(falseTarget));
				bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
				return;
			}
		}
		
		// ======================================================================
		// Third, perform (minimal) number of type tests for fields (i.e. S-DEPTH) 
		// ======================================================================
		
		// Note, we could potentially do better here by avoid multiple look ups
		// of the same field. This can happen if the field in question is used
		// for distinguishing different records (see above). 
		
		Type.Record nsrc = Type.effectiveRecordType(src);
		
		for(Map.Entry<String,Type> e : test.types.entrySet()) {
			String field = e.getKey();
			Type testType = e.getValue();
			Type srcType = nsrc.types.get(field);
			if(!Type.isSubtype(testType,srcType)) {
				// this field needs to be checked
				String nextTarget = freshLabel();
				bytecodes.add(new Bytecode.Dup(WHILEYRECORD));
				bytecodes.add(new Bytecode.LoadConst(field));				
				bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "get", fun_t , Bytecode.VIRTUAL));
				addReadConversion(srcType,bytecodes);
				translateTypeTest(nextTarget,srcType,testType,stmt,bytecodes);
				bytecodes.add(new Bytecode.Goto(falseTarget));
				bytecodes.add(new Bytecode.Label(nextTarget));
			}
		}
		
		// Ok, we have a match!
		bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
		bytecodes.add(new Bytecode.Goto(trueTarget));
		
		bytecodes.add(new Bytecode.Label(falseTarget));
		bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
	}
	
	// The following method should be replaced in future by a GLB test, using an
	// "open" record type (which currently doesn't exist).
	protected Type narrowRecordType(Type t) {

		if (t instanceof Type.Any || t instanceof Type.Void || t instanceof Type.Null
				|| t instanceof Type.Real || t instanceof Type.Int || t instanceof Type.Bool
				|| t instanceof Type.Meta || t instanceof Type.Existential
				|| t instanceof Type.Process || t instanceof Type.List
				|| t instanceof Type.Set) {
			return Type.T_VOID;
		} else if(t instanceof Type.Record) {
				return (Type) t;
		} else if(t instanceof Type.Named) {
			t = ((Type.Named)t).type;
		} else if(t instanceof Type.Recursive) {
			t = Type.unfold((Type.Recursive)t);
		}
		
		// Ok, must be union ...
						
		Type.Union u = (Type.Union) t;
		Type lub = Type.T_VOID;
		for(Type b : u.bounds) {						
			lub = Type.leastUpperBound(narrowRecordType(b),lub);			
		}
		
		return lub;
	}

	// identify the greatest type which has *exactly* those fields given.
	protected Type narrowRecordType(Type t, Set<String> fields) {
		HashMap<String,Type> types = new HashMap<String,Type>();
		for(String f : fields) {
			types.put(f, Type.T_ANY);
		}
		Type ub = Type.T_RECORD(types);				
		
		return Type.greatestLowerBound(t, ub);
	}
	
	/**
	 * <p>The following method accepts a type whose values are guaranteed to be
	 * records, and a string of required fields. It then attempts to determine
	 * the minimal number of field checks required to be sure a value of the
	 * given type has exactly the given fields.</p>
	 * 
	 * @param src
	 * @param test
	 * @return
	 */
	protected Set<String> identifyDistinguishingFields(Type src, Set<String> fields) {
		if(src instanceof Type.Recursive) {
			src = Type.unfold((Type.Recursive)src);
		}
		
		if(src instanceof Type.Record) {
			return Collections.EMPTY_SET;
		}
		
		// The real challenge with all of these methods is understanding what
		// the possible type forms are at any point.
		
		Type.Union ut = (Type.Union) src; 
		
		boolean finished = false;
		HashSet<String> solution = new HashSet<String>();
		
		while(!finished) {
			HashMap<String,Integer> conflicts = new HashMap<String,Integer>();
			
			// First, initialise conflicts
			
			for(String f : fields) {
				conflicts.put(f, 0);
			}
			
			// Second, count conflicts
			
			for(Type t : ut.bounds) {
				// FIXME: following obviously broken, as unsure if t is record
				Type.Record rt = (Type.Record) t;
				Set<String> rt_types_keySet = rt.types.keySet(); 
				if(rt_types_keySet.containsAll(solution)) {
					// only count conflicts from records not already discounted.
					for(String f : rt_types_keySet) {
						Integer conflict = conflicts.get(f);
						if(conflict != null) { conflicts.put(f, conflict+1); }
					}
				}
			}						
			
			// Third, select least conflict
			
			String chosen = null;
			int min = Integer.MAX_VALUE;
			
			// Now, identifiy least conflict
			for(Map.Entry<String,Integer> c : conflicts.entrySet()) {
				int val = c.getValue();
				String key = c.getKey();
				if(!solution.contains(key) && val < min) {
					min = val;
					chosen = key;					
				}
			}
			
			solution.add(chosen);			
			if(min == 1) { finished = true; }
		}
		
		return solution;
	}

	/**
	 * Check that a value of the given src type matches the union type given by
	 * test. The challenge here is to implement this efficiently, by avoiding
	 * unnecessarily repeating instanceof tests.
	 * 
	 * @param trueTarget
	 *            --- target branch if test succeeds
	 * @param src
	 *            --- type of expression being tested
	 * @param test
	 *            --- type of test
	 * @param stmt
	 *            --- stmt containing test (useful for line number info)
	 * @param bytecodes
	 *            --- list of bytecodes (to which test is appended)
	 */
	protected void translateTypeTest(String trueTarget, Type src, Type.Union test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) {	
		
		// FIXME: at the moment, this approach is not very efficient!
		
		String trampoline = freshLabel();
		String falseLabel = freshLabel();
		
		for(Type t : test.bounds) {
			bytecodes.add(new Bytecode.Dup(convertType(src)));
			translateTypeTest(trampoline,src,t,stmt, bytecodes);
		}
		
		bytecodes.add(new Bytecode.Pop(convertType(src)));
		bytecodes.add(new Bytecode.Goto(falseLabel));
		bytecodes.add(new Bytecode.Label(trampoline));
		bytecodes.add(new Bytecode.Pop(convertType(src)));
		bytecodes.add(new Bytecode.Goto(trueTarget));
		bytecodes.add(new Bytecode.Label(falseLabel));
		
	}

	public void translateTypeTest(String trueTarget, Type src, Type.Recursive test,
			Stmt stmt, ArrayList<Bytecode> bytecodes) { 
		
		// FACT: src is either a Type.Recursive, or a Type.Union. It cannot be
		// anything else, because otherwise the (original) glb of src and test
		// would have produced a Type.Recursive.  		
		
		if(src instanceof Type.Recursive) {
			Type.Recursive rsrc = (Type.Recursive) src;
			System.err.println("NEED TO IMPLEMENT RECURSIVE CASE");
			System.err.println("TEST: " + Type.toShortString(src) + " ~= " + Type.toShortString(test));
			System.err.println("LINE: " + filename + ":" + stmt.attribute(Attribute.Source.class));
		} else {
			// must be a union						
			
			// note, this approach is not efficient!
			Type.Union usrc = (Type.Union) src;
			Type ntest = Type.unfold(test);  
			translateTypeTest(trueTarget,usrc,ntest,stmt,bytecodes);
		}
	}
	
	public void translate(Code.Loop c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Label(c.label));
	}
	
	public void translate(Code.Forall c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {				
		translate(c.source, slots, bytecodes);
		String srcVar = "%" + c.variable.index;		
		String exitLabel = c.label + "$exit";
		String iter = freshVar(slots);
		Type srcType = c.source.type();
		Type elementType;
		if (srcType instanceof Type.Set) {
			elementType = ((Type.Set) srcType).element;
		} else {
			elementType = ((Type.List) srcType).element;
		}		
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(slots.get(iter),
				JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Label(c.label));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes
				.add(new Bytecode.Load(slots.get(iter), JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes
				.add(new Bytecode.Load(slots.get(iter), JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));
		addReadConversion(elementType, bytecodes);
		bytecodes.add(new Bytecode.Store(slots.get(srcVar),
				JAVA_LANG_OBJECT));	
	}

	protected void translate(Code.LoopEnd end,			
			HashMap<String, Integer> slots, ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Goto(end.target));
		bytecodes.add(new Bytecode.Label(end.target + "$exit"));
	}

	public void translate(Code.Goto c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Goto(c.target));
	}
	public void translate(Code.Label c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Label(c.label));
	}
	public void translate(Code.Debug c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		translate(c.rhs,slots,bytecodes);
		JvmType.Function ftype = new JvmType.Function(T_VOID,WHILEYLIST);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "println", ftype,
				Bytecode.STATIC));
	}
	public void translate(Code.Fail c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.New(JAVA_LANG_RUNTIMEEXCEPTION));
		bytecodes.add(new Bytecode.Dup(JAVA_LANG_RUNTIMEEXCEPTION));
		bytecodes.add(new Bytecode.LoadConst(c.msg));
		JvmType.Function ftype = new JvmType.Function(T_VOID, JAVA_LANG_STRING);
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_RUNTIMEEXCEPTION, "<init>",
				ftype, Bytecode.SPECIAL));
		bytecodes.add(new Bytecode.Throw());
	}
	public void translate(Code.ExternJvm c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.addAll(c.bytecodes);
	}
	
	public void translate(CExpr r, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		if (r instanceof Value) {
			translate((Value) r, slots, bytecodes);
		} else if(r instanceof CExpr.Variable) {
			translate((CExpr.Variable) r,slots,bytecodes);			
		} else if(r instanceof CExpr.Register) {			
			translate((CExpr.Register) r,slots,bytecodes);
		} else if(r instanceof CExpr.ListAccess) {
			translate((CExpr.ListAccess)r,slots,bytecodes);
		} else if(r instanceof CExpr.BinOp) {
			translate((CExpr.BinOp)r,slots,bytecodes);
		} else if(r instanceof CExpr.UnOp) {
			translate((CExpr.UnOp)r,slots,bytecodes);
		} else if(r instanceof CExpr.NaryOp) {
			translate((CExpr.NaryOp)r,slots,bytecodes);
		} else if(r instanceof CExpr.Dictionary) {
			translate((CExpr.Dictionary)r,slots,bytecodes);
		}  else if(r instanceof CExpr.Record) {
			translate((CExpr.Record)r,slots,bytecodes);
		} else if(r instanceof CExpr.RecordAccess) {
			translate((CExpr.RecordAccess)r,slots,bytecodes);
		} else if(r instanceof CExpr.Invoke) {
			translate((CExpr.Invoke)r,slots,bytecodes);
		} else {
			throw new RuntimeException("Unknown expression encountered: " + r);
		}
	}
		
	public void translate(CExpr.Variable v, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {		
		bytecodes.add(new Bytecode.Load(slots.get(v.name),
				convertType(v.type)));
	}
	public void translate(CExpr.Register v, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.Load(slots.get("%" + v.index),
				convertType(v.type)));
	}
	public void translate(CExpr.ListAccess v, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {		
		translate(v.src,slots,bytecodes);		
		translate(v.index,slots,bytecodes);				
		Type src_t = v.src.type();
		
		if (Type.isSubtype(Type.T_LIST(Type.T_ANY), src_t)) {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
					BIG_RATIONAL);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "get", ftype,
					Bytecode.VIRTUAL));
		} else {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
					JAVA_LANG_OBJECT);
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "get", ftype,
					Bytecode.VIRTUAL));
		}

		addReadConversion(v.type(),bytecodes);	
	}
	
	public void translate(CExpr.RecordAccess c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		translate(c.lhs, slots, bytecodes);
		
		bytecodes.add(new Bytecode.LoadConst(c.field));
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"get",ftype,Bytecode.VIRTUAL));				
		Type et = c.type();		
		addReadConversion(et,bytecodes);
	}

	public void translate(CExpr.Record expr, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);
		construct(WHILEYRECORD, slots, bytecodes);		
		for(Map.Entry<String,CExpr> e : expr.values.entrySet()) {
			Type et = e.getValue().type();
			bytecodes.add(new Bytecode.Dup(WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(e.getKey()));
			translate(e.getValue(), slots, bytecodes);
			addWriteConversion(et,bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"put",ftype,Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
		}
	}

	protected void translate(CExpr.Dictionary expr, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		
		construct(WHILEYMAP, slots, bytecodes);
		
		for (Pair<CExpr, CExpr> e : expr.values) {		
			Type kt = e.first().type();
			Type vt = e.second().type();
			bytecodes.add(new Bytecode.Dup(WHILEYMAP));			
			translate(e.first(), slots, bytecodes);
			addWriteConversion(kt, bytecodes);
			translate(e.second(), slots, bytecodes);
			addWriteConversion(vt, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(WHILEYMAP));
		}
	}	
	
	public void translate(CExpr.BinOp c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {				
		
		Type lub = Type.leastUpperBound(c.lhs.type(),c.rhs.type());
		
		translate(c.lhs, slots, bytecodes);
		
		convert(lub,c.lhs.type(),slots,bytecodes);
		
		translate(c.rhs, slots, bytecodes);

		convert(lub,c.rhs.type(),slots,bytecodes);
		
		JvmType type = convertType(lub);
		JvmType.Function ftype = new JvmType.Function(type,type);
		
		switch(c.op) {
		case ADD:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "add", ftype,
					Bytecode.VIRTUAL));
			break;
		case SUB:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "subtract", ftype,
					Bytecode.VIRTUAL));
			break;
		case MUL:			
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "multiply", ftype,
					Bytecode.VIRTUAL));
			break;
		case DIV:						
			if(lub == Type.T_INT) {
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "intDivide", ftype,
						Bytecode.VIRTUAL));
			} else {
				bytecodes.add(new Bytecode.Invoke((JvmType.Clazz)type, "divide", ftype,
					Bytecode.VIRTUAL));
			}
			break;
		case UNION:			
			bytecodes.add(new Bytecode.Invoke(WHILEYSET, "union", ftype,
					Bytecode.VIRTUAL));
			break;
		case INTERSECT:
			bytecodes.add(new Bytecode.Invoke(WHILEYSET, "intersect", ftype,
					Bytecode.VIRTUAL));
			break;
		case DIFFERENCE:
			bytecodes.add(new Bytecode.Invoke(WHILEYSET, "difference", ftype,
					Bytecode.VIRTUAL));
			break;
		case APPEND:			
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "append", ftype,
					Bytecode.VIRTUAL));
			break;
		}		
	}

	public void translate(CExpr.UnOp c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {				
				
		JvmType type = convertType(c.type());

		switch (c.op) {
		case NEG: {
			translate(c.rhs, slots, bytecodes);
			JvmType.Function ftype = new JvmType.Function(type);
			bytecodes.add(new Bytecode.Invoke((JvmType.Clazz) type, "negate",
					ftype, Bytecode.VIRTUAL));
			break;
		}	
		case LENGTHOF: {
			translate(c.rhs, slots, bytecodes);
			JvmType.Function ftype = new JvmType.Function(T_INT);			
			if(Type.isSubtype(Type.T_LIST(Type.T_ANY),c.rhs.type())) {
				bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "size",
						ftype, Bytecode.VIRTUAL));
			} else {
				bytecodes.add(new Bytecode.Invoke(WHILEYSET, "size",
					ftype, Bytecode.VIRTUAL));
			}			
			ftype = new JvmType.Function(BIG_RATIONAL, T_INT);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf",
					ftype, Bytecode.STATIC));
			break;
		}
		case PROCESSSPAWN:
		{
			bytecodes.add(new Bytecode.New(WHILEYPROCESS));
			bytecodes.add(new Bytecode.Dup(WHILEYPROCESS));
			translate(c.rhs, slots, bytecodes);				
			JvmType.Function ftype = new JvmType.Function(T_VOID,JAVA_LANG_OBJECT);
			bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "<init>", ftype,
					Bytecode.SPECIAL));
			break;
		}
		case PROCESSACCESS:
		{
			translate(c.rhs, slots, bytecodes);
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT);		
			bytecodes.add(new Bytecode.Invoke(WHILEYPROCESS, "state", ftype,
					Bytecode.VIRTUAL));
			// finally, we need to cast the object we got back appropriately.		
			Type t = (Type.ProcessName) c.rhs.type();			
			if(t instanceof Type.Named) {
				t = ((Type.Named) t).type;					
			} 
			Type.Process pt = (Type.Process) t; 
			addReadConversion(pt.element, bytecodes);
			break;
		}
		}		
	}
	public void translate(CExpr.NaryOp c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {

		switch (c.op) {
		case SETGEN: {
			construct(WHILEYSET, slots, bytecodes);		
			JvmType.Function ftype = new JvmType.Function(T_BOOL,
					JAVA_LANG_OBJECT);  
			for(CExpr e : c.args) {
				bytecodes.add(new Bytecode.Dup(WHILEYSET));
				translate(e, slots, bytecodes);
				addWriteConversion(e.type(),bytecodes);
				bytecodes.add(new Bytecode.Invoke(WHILEYSET,"add",ftype,Bytecode.VIRTUAL));
				// FIXME: there is a bug here for bool lists
				bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
			}
			break;
		}
		case LISTGEN: {
			construct(WHILEYLIST, slots, bytecodes);		
			JvmType.Function ftype = new JvmType.Function(T_BOOL,
					JAVA_LANG_OBJECT);  

			for(CExpr e : c.args) {
				bytecodes.add(new Bytecode.Dup(WHILEYLIST));
				translate(e, slots, bytecodes);
				addWriteConversion(e.type(),bytecodes);
				bytecodes.add(new Bytecode.Invoke(WHILEYLIST,"add",ftype,Bytecode.VIRTUAL));
				// FIXME: there is a bug here for bool lists
				bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
			}

			break;
		}
		case SUBLIST: {
			// translate right-hand side
			for(CExpr r : c.args) {
				translate(r, slots, bytecodes);			
			}

			JvmType.Function ftype = new JvmType.Function(WHILEYLIST,BIG_RATIONAL,BIG_RATIONAL);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "sublist", ftype,
					Bytecode.VIRTUAL));
			break;
		}
		}	
	}
	
	public void translate(CExpr.Invoke c, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {				
		// first, translate receiver (where appropriate)
		if(c.type.receiver != null) {						
			translate(c.receiver, slots, bytecodes);						
		}
		
		// next, translate parameters
		List<Type> params = c.type.params;
		for(int i=0;i!=params.size();++i) {
			Type pt = params.get(i);
			CExpr r = c.args.get(i);
			Type rt = r.type();
			translate(r, slots, bytecodes);			
			if(!pt.equals(rt)) {
				convert(pt,rt,slots,bytecodes);				
			} else {
				cloneRHS(rt,bytecodes);
			}
		}
		ModuleID mid = c.name.module();
		JvmType.Clazz owner = new JvmType.Clazz(mid.pkg().toString(),mid.module());
		JvmType.Function type = (JvmType.Function) convertType(c.type);
		String mangled = nameMangle(c.name.name(), c.type);		
		if(c.caseNum > 0) {
			mangled += "$" + c.caseNum;
		}
		bytecodes.add(new Bytecode.Invoke(owner, mangled, type,
				Bytecode.STATIC));				
	}
		
	public void translate(Value v, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		if(v instanceof Value.Null) {
			translate((Value.Null)v,slots,bytecodes);
		} else if(v instanceof Value.Bool) {
			translate((Value.Bool)v,slots,bytecodes);
		} else if(v instanceof Value.Int) {
			translate((Value.Int)v,slots,bytecodes);
		} else if(v instanceof Value.Real) {
			translate((Value.Real)v,slots,bytecodes);
		} else if(v instanceof Value.Set) {
			translate((Value.Set)v,slots,bytecodes);
		} else if(v instanceof Value.List) {
			translate((Value.List)v,slots,bytecodes);
		} else if(v instanceof Value.Record) {
			translate((Value.Record)v,slots,bytecodes);
		} else if(v instanceof Value.Dictionary) {
			translate((Value.Dictionary)v,slots,bytecodes);
		} else {
			throw new IllegalArgumentException("unknown value encountered:" + v);
		}
	}
	
	protected void translate(Value.Null e, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		bytecodes.add(new Bytecode.LoadConst(null));
	}
	
	protected void translate(Value.Bool e, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		if (e.value) {
			bytecodes.add(new Bytecode.LoadConst(1));
		} else {
			bytecodes.add(new Bytecode.LoadConst(0));
		}
	}
	protected void translate(Value.Int e, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		BigInteger v = e.value;
		
		if(v.bitLength() < 32) {			
			bytecodes.add(new Bytecode.LoadConst(v.intValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else if(v.bitLength() < 64) {			
			bytecodes.add(new Bytecode.LoadConst(v.longValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_LONG);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else {
			// in this context, we need to use a byte array to construct the
			// integer object.
			byte[] bytes = v.toByteArray();
			JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);
			
			bytecodes.add(new Bytecode.New(BIG_RATIONAL));		
			bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}			
			
			JvmType.Function ftype = new JvmType.Function(T_VOID,bat);						
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "<init>", ftype,
					Bytecode.SPECIAL));
		}		
	}

	protected void translate(Value.Real e, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		BigRational rat = e.value;
		BigInteger den = rat.denominator();
		BigInteger num = rat.numerator();
		if(den.equals(BigRational.ONE)) {
			if(num.bitLength() < 32) {			
				bytecodes.add(new Bytecode.LoadConst(num.intValue()));				
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT);
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
						Bytecode.STATIC));
			} else if(num.bitLength() < 64) {			
				bytecodes.add(new Bytecode.LoadConst(num.longValue()));				
				JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_LONG);
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
						Bytecode.STATIC));
			} else {
				// in this context, we need to use a byte array to construct the
				// integer object.
				byte[] bytes = num.toByteArray();
				JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);
				
				bytecodes.add(new Bytecode.New(BIG_RATIONAL));		
				bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));			
				bytecodes.add(new Bytecode.LoadConst(bytes.length));
				bytecodes.add(new Bytecode.New(bat));
				for(int i=0;i!=bytes.length;++i) {
					bytecodes.add(new Bytecode.Dup(bat));
					bytecodes.add(new Bytecode.LoadConst(i));
					bytecodes.add(new Bytecode.LoadConst(bytes[i]));
					bytecodes.add(new Bytecode.ArrayStore(bat));
				}			
				
				JvmType.Function ftype = new JvmType.Function(T_VOID,bat);						
				bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "<init>", ftype,
						Bytecode.SPECIAL));								
			}	
		} else if(num.bitLength() < 32 && den.bitLength() < 32) {			
			bytecodes.add(new Bytecode.LoadConst(num.intValue()));
			bytecodes.add(new Bytecode.LoadConst(den.intValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_INT,T_INT);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else if(num.bitLength() < 64 && den.bitLength() < 64) {			
			bytecodes.add(new Bytecode.LoadConst(num.longValue()));
			bytecodes.add(new Bytecode.LoadConst(den.longValue()));
			JvmType.Function ftype = new JvmType.Function(BIG_RATIONAL,T_LONG,T_LONG);
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "valueOf", ftype,
					Bytecode.STATIC));
		} else {
			// First, do numerator bytes
			byte[] bytes = num.toByteArray();
			JvmType.Array bat = new JvmType.Array(JvmTypes.T_BYTE);
			
			bytecodes.add(new Bytecode.New(BIG_RATIONAL));		
			bytecodes.add(new Bytecode.Dup(BIG_RATIONAL));			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}		
			
			// Second, do denominator bytes
			bytes = den.toByteArray();			
			bytecodes.add(new Bytecode.LoadConst(bytes.length));
			bytecodes.add(new Bytecode.New(bat));
			for(int i=0;i!=bytes.length;++i) {
				bytecodes.add(new Bytecode.Dup(bat));
				bytecodes.add(new Bytecode.LoadConst(i));
				bytecodes.add(new Bytecode.LoadConst(bytes[i]));
				bytecodes.add(new Bytecode.ArrayStore(bat));
			}
			
			// Finally, construct BigRational object
			JvmType.Function ftype = new JvmType.Function(T_VOID,bat,bat);						
			bytecodes.add(new Bytecode.Invoke(BIG_RATIONAL, "<init>", ftype,
					Bytecode.SPECIAL));			
		}		
	}

	protected void translate(Value.Set lv, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		construct(WHILEYSET, slots, bytecodes);		
		JvmType.Function ftype = new JvmType.Function(T_BOOL,
				JAVA_LANG_OBJECT);  
		for(Value e : lv.values) {
			// FIXME: there is a bug here for bool sets
			bytecodes.add(new Bytecode.Dup(WHILEYSET));
			translate(e, slots, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYSET,"add",ftype,Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
		}
	}

	protected void translate(Value.List lv, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		construct(WHILEYLIST, slots, bytecodes);
		JvmType.Function ftype = new JvmType.Function(T_BOOL, JAVA_LANG_OBJECT);		
		for (Value e : lv.values) {
			bytecodes.add(new Bytecode.Dup(WHILEYLIST));
			translate(e, slots, bytecodes);
			addWriteConversion(e.type(), bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "add", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JvmTypes.T_BOOL));
		}		
	}

	protected void translate(Value.Record expr, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		construct(WHILEYRECORD, slots, bytecodes);
		for (Map.Entry<String, Value> e : expr.values.entrySet()) {
			Type et = e.getValue().type();
			bytecodes.add(new Bytecode.Dup(WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(e.getKey()));
			translate(e.getValue(), slots, bytecodes);
			addWriteConversion(et, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(WHILEYRECORD));
		}
	}
	
	protected void translate(Value.Dictionary expr, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
				JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
		
		construct(WHILEYMAP, slots, bytecodes);
		
		for (Map.Entry<Value, Value> e : expr.values.entrySet()) {
			Type kt = e.getKey().type();
			Type vt = e.getValue().type();
			bytecodes.add(new Bytecode.Dup(WHILEYMAP));			
			translate(e.getKey(), slots, bytecodes);
			addWriteConversion(kt, bytecodes);
			translate(e.getValue(), slots, bytecodes);
			addWriteConversion(vt, bytecodes);
			bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(WHILEYMAP));
		}
	}
	
	public void makePreAssignment(LVal lhs, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		if(lhs instanceof CExpr.ListAccess) {
			CExpr.ListAccess v = (CExpr.ListAccess) lhs;
			translate(v.src,slots,bytecodes);			
			translate(v.index,slots,bytecodes);								
		} else if(lhs instanceof CExpr.RecordAccess) {
			CExpr.RecordAccess la = (CExpr.RecordAccess) lhs;
			translate(la.lhs, slots, bytecodes);
			// FIXME: new any type is a hack, but it works
			convert(Type.T_ANY,la.lhs.type(),slots, bytecodes);
			bytecodes.add(new Bytecode.LoadConst(la.field));
		}
	}
	
	public void makePostAssignment(LVal lhs, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		cloneRHS(lhs.type(),bytecodes);
		if(lhs instanceof CExpr.Variable) {
			CExpr.Variable v = (CExpr.Variable) lhs;
			bytecodes.add(new Bytecode.Store(slots.get(v.name),
					convertType(v.type)));
		} else if(lhs instanceof CExpr.Register) {
			CExpr.Register v = (CExpr.Register) lhs;			
			bytecodes.add(new Bytecode.Store(slots.get("%" + v.index),
					convertType(v.type)));
		} else if(lhs instanceof CExpr.ListAccess) {
			CExpr.ListAccess v = (CExpr.ListAccess) lhs;
			Type src_t = v.src.type();
			if(src_t instanceof Type.List) { 
				Type.List lt = (Type.List) v.src.type();
				addWriteConversion(lt.element,bytecodes);			
				JvmType.Function ftype = new JvmType.Function(T_VOID,BIG_RATIONAL,JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "set", ftype,
						Bytecode.VIRTUAL));					
			} else {
				Type.Dictionary lt = (Type.Dictionary) v.src.type();
				addWriteConversion(lt.value,bytecodes);			
				JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
						JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
				bytecodes.add(new Bytecode.Invoke(WHILEYMAP, "put", ftype,
						Bytecode.VIRTUAL));
			}
		} else if(lhs instanceof CExpr.RecordAccess) {		
			CExpr.RecordAccess la = (CExpr.RecordAccess) lhs;
			Type.Record tt = (Type.Record) Type.effectiveRecordType(la.lhs.type());
			Type element_t = tt.types.get(la.field);
			addWriteConversion(element_t, bytecodes);
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,
					JAVA_LANG_OBJECT, JAVA_LANG_OBJECT);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "put", ftype,
					Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		} else {
			throw new RuntimeException("Unknown lval encountered in assignment");
		}
	}

	/**
	 * The cloneRHS method is responsible for cloning the right-hand side of an
	 * assignment operation. This is necessary to ensure the correct value
	 * semantics of wyil are preserved. An interesting question is how we might
	 * avoid such cloning.
	 * 
	 * @param t
	 * @param bytecodes
	 */
	private void cloneRHS(Type t, ArrayList<Bytecode> bytecodes) {
		// Now, for list, set and record types we need to clone the object in
		// question. In fact, this could be optimised in some situations
		// where we know the old variable is not live.
		if (t instanceof Type.List) {
			JvmType.Function ftype = new JvmType.Function(WHILEYLIST);
			bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "clone", ftype,
					Bytecode.VIRTUAL));
		} else if (t instanceof Type.Set) {
			JvmType.Function ftype = new JvmType.Function(WHILEYSET);
			bytecodes.add(new Bytecode.Invoke(WHILEYSET, "clone", ftype,
					Bytecode.VIRTUAL));
		} else if (t instanceof Type.Record) {
			JvmType.Function ftype = new JvmType.Function(WHILEYRECORD);
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD, "clone", ftype,
					Bytecode.VIRTUAL));
		}
	}

	
	/**
	 * The read conversion is necessary in situations where we're reading a
	 * value from a collection (e.g. WhileyList, WhileySet, etc) and then
	 * putting it on the stack. In such case, we need to convert boolean values
	 * from Boolean objects to bool primitives.
	 */
	public void addReadConversion(Type et, ArrayList<Bytecode> bytecodes) {
		if(et instanceof Type.Bool) {
			bytecodes.add(new Bytecode.CheckCast(JAVA_LANG_BOOLEAN));
			JvmType.Function ftype = new JvmType.Function(T_BOOL);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,
					"booleanValue", ftype, Bytecode.VIRTUAL));
		} else {	
			addCheckCast(convertType(et),bytecodes);			
		}
	}

	/**
	 * The write conversion is necessary in situations where we're write a value
	 * from the stack into a collection (e.g. WhileyList, WhileySet, etc). In
	 * such case, we need to convert boolean values from bool primitives to
	 * Boolean objects.
	 */
	public void addWriteConversion(Type et, ArrayList<Bytecode> bytecodes) {
		if(et instanceof Type.Bool) {
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BOOLEAN,T_BOOL);
			bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,
					"valueOf", ftype, Bytecode.STATIC));
		} 
	}

	public void addCheckCast(JvmType type, ArrayList<Bytecode> bytecodes) {
		// The following can happen in situations where a variable has type
		// void. In principle, we could remove this as obvious dead-code, but
		// for now I just avoid it.
		if(type instanceof JvmType.Void) {
			return;
		}
		bytecodes.add(new Bytecode.CheckCast(type));
	}
	
	/**
	 * The construct method provides a generic way to construct a Java object.
	 * 
	 * @param owner
	 * @param slots
	 * @param bytecodes
	 * @param params
	 */
	public void construct(JvmType.Clazz owner, HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes, CExpr... params) {
		bytecodes.add(new Bytecode.New(owner));		
		bytecodes.add(new Bytecode.Dup(owner));
		ArrayList<JvmType> paramTypes = new ArrayList<JvmType>();
		for(CExpr e : params) {			
			translate(e,slots,bytecodes);
			paramTypes.add(convertType(e.type()));
		}
		JvmType.Function ftype = new JvmType.Function(T_VOID,paramTypes);
		
		// call the appropriate constructor
		bytecodes.add(new Bytecode.Invoke(owner, "<init>", ftype,
				Bytecode.SPECIAL));
	}		 
	
	protected void convert(Type toType, Type fromType,
			HashMap<String, Integer> slots, ArrayList<Bytecode> bytecodes) {				
		if(toType.equals(fromType)) {		
			// do nothing!						
		} else if (!(toType instanceof Type.Bool) && fromType instanceof Type.Bool) {
			// this is either going into a union type, or the any type
			convert(toType, (Type.Bool) fromType, slots, bytecodes);
		} else if(toType == Type.T_REAL && fromType == Type.T_INT) {			
			
			// no longer need to convert between them!
			
		} else if(toType instanceof Type.List && fromType instanceof Type.List) {
			convert((Type.List) toType, (Type.List) fromType, slots, bytecodes);			
		} else if(toType instanceof Type.Set && fromType instanceof Type.List) {
			convert((Type.Set) toType, (Type.List) fromType, slots, bytecodes);			
		} else if(toType instanceof Type.Set && fromType instanceof Type.Set) {
			convert((Type.Set) toType, (Type.Set) fromType, slots, bytecodes);			
		} else if(toType instanceof Type.Record && fromType instanceof Type.Record) {
			convert((Type.Record) toType, (Type.Record) fromType, slots, bytecodes);
		} else {
			// every other kind of conversion is either a syntax error (which
			// should have been caught by TypeChecker); or, a nop (since no
			// conversion is required on this particular platform).
		}
	}
	
	protected void convert(Type.List toType, Type.List fromType,
			HashMap<String, Integer> slots,			
			ArrayList<Bytecode> bytecodes) {
		
		if(fromType.element == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		String iter = freshVar(slots);
		String tmp = freshVar(slots);
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(slots.get(iter),
				JAVA_UTIL_ITERATOR));
		construct(WHILEYLIST,slots,bytecodes);
		bytecodes.add(new Bytecode.Store(slots.get(tmp), WHILEYLIST));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYLIST));
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));						
		addReadConversion(fromType.element,bytecodes);
		convert(toType.element, fromType.element, slots,
				bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYLIST, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYLIST));
	}
	
	protected void convert(Type.Set toType, Type.List fromType,
			HashMap<String, Integer> slots,			
			ArrayList<Bytecode> bytecodes) {
						
		if(fromType.element == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		String iter = freshVar(slots);
		String tmp = freshVar(slots);
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(slots.get(iter),
				JAVA_UTIL_ITERATOR));
		construct(WHILEYSET,slots,bytecodes);
		bytecodes.add(new Bytecode.Store(slots.get(tmp), WHILEYSET));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYSET));
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));						
		addReadConversion(fromType.element,bytecodes);
		convert(toType.element, fromType.element, slots,
				bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYSET));
	}
	
	protected void convert(Type.Set toType, Type.Set fromType,
			HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		
		if(fromType.element == Type.T_VOID) {
			// nothing to do, in this particular case
			return;
		}
		
		// The following piece of code implements a java for-each loop which
		// iterates every element of the input collection, and recursively
		// converts it before loading it back onto a new WhileyList. 
		String loopLabel = freshLabel();
		String exitLabel = freshLabel();
		String iter = freshVar(slots);
		String tmp = freshVar(slots);
		JvmType.Function ftype = new JvmType.Function(JAVA_UTIL_ITERATOR);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_COLLECTION, "iterator",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.Store(slots.get(iter),
				JAVA_UTIL_ITERATOR));
		construct(WHILEYSET,slots,bytecodes);
		bytecodes.add(new Bytecode.Store(slots.get(tmp), WHILEYSET));
		bytecodes.add(new Bytecode.Label(loopLabel));
		ftype = new JvmType.Function(T_BOOL);
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "hasNext",
				ftype, Bytecode.INTERFACE));
		bytecodes.add(new Bytecode.If(Bytecode.If.EQ, exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYSET));
		bytecodes.add(new Bytecode.Load(slots.get(iter),JAVA_UTIL_ITERATOR));
		ftype = new JvmType.Function(JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(JAVA_UTIL_ITERATOR, "next",
				ftype, Bytecode.INTERFACE));
		addCheckCast(convertType(fromType.element),bytecodes);		
		convert(toType.element, fromType.element, slots,
				bytecodes);			
		ftype = new JvmType.Function(T_BOOL,JAVA_LANG_OBJECT);
		bytecodes.add(new Bytecode.Invoke(WHILEYSET, "add",
				ftype, Bytecode.VIRTUAL));
		bytecodes.add(new Bytecode.Pop(T_BOOL));
		bytecodes.add(new Bytecode.Goto(loopLabel));
		bytecodes.add(new Bytecode.Label(exitLabel));
		bytecodes.add(new Bytecode.Load(slots.get(tmp),WHILEYSET));
	}
	
	public void convert(Type.Record toType, Type.Record fromType,
			HashMap<String, Integer> slots,
			ArrayList<Bytecode> bytecodes) {
		slots = (HashMap) slots.clone();
		String oldtup = freshVar(slots);
		String newtup = freshVar(slots);
		bytecodes.add(new Bytecode.Store(slots.get(oldtup),WHILEYRECORD));
		construct(WHILEYRECORD,slots,bytecodes);
		bytecodes.add(new Bytecode.Store(slots.get(newtup),WHILEYRECORD));		
				
		for(String key : toType.types.keySet()) {
			Type to = toType.types.get(key);
			Type from = fromType.types.get(key);					
			bytecodes.add(new Bytecode.Load(slots.get(newtup),WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(key));
			bytecodes.add(new Bytecode.Load(slots.get(oldtup),WHILEYRECORD));
			bytecodes.add(new Bytecode.LoadConst(key));
			JvmType.Function ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"get",ftype,Bytecode.VIRTUAL));								
			addReadConversion(from,bytecodes);			
			if(!to.equals(from)) {
				// now perform recursive conversion
				convert(to,from,slots,bytecodes);
			}			
			ftype = new JvmType.Function(JAVA_LANG_OBJECT,JAVA_LANG_OBJECT,JAVA_LANG_OBJECT);			
			bytecodes.add(new Bytecode.Invoke(WHILEYRECORD,"put",ftype,Bytecode.VIRTUAL));
			bytecodes.add(new Bytecode.Pop(JAVA_LANG_OBJECT));
		}
		bytecodes.add(new Bytecode.Load(slots.get(newtup),WHILEYRECORD));		
	}
	
	public void convert(Type toType, Type.Bool fromType,
			HashMap<String, Integer> slots, ArrayList<Bytecode> bytecodes) {
		JvmType.Function ftype = new JvmType.Function(JAVA_LANG_BOOLEAN,T_BOOL);			
		bytecodes.add(new Bytecode.Invoke(JAVA_LANG_BOOLEAN,"valueOf",ftype,Bytecode.STATIC));			
		// done deal!
	}
	
	
	public final static JvmType.Clazz WHILEYLIST = new JvmType.Clazz("wyil.jvm.rt","WhileyList");
	public final static JvmType.Clazz WHILEYSET = new JvmType.Clazz("wyil.jvm.rt","WhileySet");
	public final static JvmType.Clazz WHILEYMAP = new JvmType.Clazz("java.util","HashMap");
	public final static JvmType.Clazz WHILEYRECORD = new JvmType.Clazz("wyil.jvm.rt","WhileyRecord");	
	public final static JvmType.Clazz WHILEYPROCESS = new JvmType.Clazz(
			"wyil.jvm.rt", "WhileyProcess");	
	public final static JvmType.Clazz BIG_RATIONAL = new JvmType.Clazz("wyil.jvm.rt","BigRational");
	private static final JvmType.Clazz JAVA_LANG_SYSTEM = new JvmType.Clazz("java.lang","System");
	private static final JvmType.Clazz JAVA_IO_PRINTSTREAM = new JvmType.Clazz("java.io","PrintStream");
	private static final JvmType.Clazz JAVA_LANG_RUNTIMEEXCEPTION = new JvmType.Clazz("java.lang","RuntimeException");
	private static final JvmType.Clazz JAVA_LANG_ASSERTIONERROR = new JvmType.Clazz("java.lang","AssertionError");
	private static final JvmType.Clazz JAVA_UTIL_COLLECTION = new JvmType.Clazz("java.util","Collection");	
	
	public JvmType convertType(Type t) {
		if(t == Type.T_VOID) {
			return T_VOID;
		} else if(t == Type.T_ANY) {
			return JAVA_LANG_OBJECT;
		} else if(t == Type.T_NULL) {
			return JAVA_LANG_OBJECT;
		} else if(t instanceof Type.Bool) {
			return T_BOOL;
		} else if(t instanceof Type.Int) {
			return BIG_RATIONAL;
		} else if(t instanceof Type.Real) {
			return BIG_RATIONAL;
		} else if(t instanceof Type.List) {
			return WHILEYLIST;
		} else if(t instanceof Type.Set) {
			return WHILEYSET;
		} else if(t instanceof Type.Dictionary) {
			return WHILEYMAP;
		} else if(t instanceof Type.Record) {
			return WHILEYRECORD;
		} else if(t instanceof Type.Process) {
			return WHILEYPROCESS;
		} else if(t instanceof Type.Named) {
			Type.Named nt = (Type.Named) t;
			return convertType(nt.type);
		} else if(t instanceof Type.Union) {
			// There's an interesting question as to whether we need to do more
			// here. For example, a union of a set and a list could result in
			// contains ?
			Type.Record tt = Type.effectiveRecordType(t);
			if(tt != null) {
				return WHILEYRECORD;
			} else {
				return JAVA_LANG_OBJECT;
			}
		} else if(t instanceof Type.Meta) {							
			return JAVA_LANG_OBJECT;			
		} else if(t instanceof Type.Recursive) {
			Type.Recursive rt = (Type.Recursive) t;
			if(rt.type == null) {
				return JAVA_LANG_OBJECT;
			} else {
				return convertType(rt.type);
			}
		} else if(t instanceof Type.Fun) {
			Type.Fun ft = (Type.Fun) t; 
			ArrayList<JvmType> paramTypes = new ArrayList<JvmType>();
			if(ft.receiver != null) {
				paramTypes.add(convertType(ft.receiver));
			}
			for(Type pt : ft.params) {
				paramTypes.add(convertType(pt));
			}
			JvmType rt = convertType(ft.ret);
			return new JvmType.Function(rt,paramTypes);
		}else {
			throw new RuntimeException("unknown type encountered: " + t);
		}		
	}	
		
	protected int var = 0;
	protected String freshVar(HashMap<String, Integer> slots) {
		// Note, I use "$$" here to avoid name clases with temp variables
        // generated by the front-end.
		String v = "$$" + var++; 
		slots.put(v, slots.size());
		return v;
	}
	
	protected int label = 0;
	protected String freshLabel() {
		return "cfblab" + label++;
	}
	
	protected String nameMangle(String name, Type.Fun type) {		
		return name + "$" + type2str(type);		
	}
	
	protected String type2str(Type t) {
		if(t == Type.T_EXISTENTIAL) {
			return "?";
		} else if(t == Type.T_ANY) {
			return "*";
		} else if(t == Type.T_VOID) {
			return "V";
		} else if(t == Type.T_NULL) {
			return "O";
		} else if(t instanceof Type.Bool) {
			return "B";
		} else if(t instanceof Type.Int) {
			return "I";
		} else if(t instanceof Type.Real) {
			return "R";
		} else if(t instanceof Type.List) {
			Type.List st = (Type.List) t;
			return "[" + type2str(st.element) + "]";
		} else if(t instanceof Type.Set) {
			Type.Set st = (Type.Set) t;
			return "{" + type2str(st.element) + "}";
		} else if(t instanceof Type.Dictionary) {
			Type.Dictionary st = (Type.Dictionary) t;
			return "{" + type2str(st.key) + "->" + type2str(st.value) + "}";
		} else if(t instanceof Type.Union) {
			Type.Union st = (Type.Union) t;
			String r = "";
			boolean firstTime=true;
			for(Type b : st.bounds) {
				if(!firstTime) {
					r += "|";
				}
				firstTime=false;
				r += type2str(b);
			}			
			return r;
		} else if(t instanceof Type.Record) {
			Type.Record st = (Type.Record) t;
			ArrayList<String> keys = new ArrayList<String>(st.types.keySet());
			Collections.sort(keys);
			String r="(";
			for(String k : keys) {
				Type kt = st.types.get(k);
				r += k + ":" + type2str(kt);
			}			
			return r + ")";
		} else if(t instanceof Type.Process) {
			Type.Process st = (Type.Process) t;
			return "P" + type2str(st.element);
		} else if(t instanceof Type.Recursive) {
			Type.Recursive rt = (Type.Recursive) t;
			if(rt.type == null) {
				return rt.name.module() + ";" + rt.name.name();				
			} else {
				return "U" + rt.name.module() + ";" + rt.name.name() + ";"
				+ type2str(rt.type);				
			}
		} else if(t instanceof Type.Named) {
			Type.Named st = (Type.Named) t;
			return "N" + st.name.module() + ";" + st.name.name() + ";"
					+ type2str(st.type);
		} else if(t instanceof Type.Fun) {
			Type.Fun ft = (Type.Fun) t;
			String r = "";
			if(ft.receiver != null) {
				r += type2str(ft.receiver) + "$";
			}				
			r += type2str(ft.ret);
			for(Type pt : ft.params) {
				r += type2str(pt);
			}
			return r;
		} else {
			throw new RuntimeException("unknown type encountered: " + t);
		}
	}
}