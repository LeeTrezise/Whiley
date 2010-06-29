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

package wyjc.ast.types;

import wyone.core.WType;
import wyjc.util.NameID;
import java.util.*;

/**
 * This represents a primitive type in the language, such as "int" or "void".
 * 
 * @author djp
 */
public interface Type extends UnresolvedType { 
	/**
	 * Determine whether a given type is a subtype of this type.
	 * 
	 * @param t
	 * @return
	 */
	public abstract boolean isSubtype(Type t, Map<NameID,Type> environment);
	
	/**
	 * Strip off all named types.
	 */
	public abstract Type flattern();
	
	/**
	 * Substitute matching type variables for new names 
	 * @param binding
	 * @return
	 */
	public abstract Type substitute(Map<NameID,NameID> binding);

	/**
	 * Return every subcomponent of this type which is an instanceof of the
	 * given class.
	 * 
	 * @param <T>
	 * @param type
	 * @return
	 */
	public abstract <T> Set<T> match(Class<T> type);
	
	public abstract boolean isExistential(); 	
	
	public abstract WType convert();
}
