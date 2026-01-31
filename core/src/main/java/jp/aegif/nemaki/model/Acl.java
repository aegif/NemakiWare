/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

//This class need to be calculated for the path of inherited ACEs
public class Acl implements Serializable {
	private static final long serialVersionUID = 1L;
	private List<Ace> inheritedAces;
	private List<Ace> localAces;

	public Acl(){
		inheritedAces = new ArrayList<Ace>();
		localAces = new ArrayList<Ace>();
	}

	public List<Ace> getInheritedAces() {
		return inheritedAces;
	}
	public void setInheritedAces(List<Ace> inheritedAces) {
		this.inheritedAces = inheritedAces;
	}
	public List<Ace> getLocalAces() {
		return localAces;
	}
	public void setLocalAces(List<Ace> localAces) {
		this.localAces = localAces;
	}

	public List<Ace> getAllAces(){
		return getMergedAces();
	}

	public List<Ace>getPropagatingAces(){
		List<Ace> merged = new ArrayList<Ace>(inheritedAces);
		//merged.add(ace);
		return merged;
	}

	public List<Ace> getMergedAces(){
		HashMap<String, Ace> _result = buildMap(localAces);
		HashMap<String, Ace> localMap = buildMap(localAces);
		HashMap<String, Ace> inheritedMap = buildMap(inheritedAces);

		for(Entry<String, Ace> i : inheritedMap.entrySet()){
			if(!localMap.containsKey(i.getKey())){
				_result.put(i.getKey(), i.getValue());
			}
		}

		//Convert map to list
		List<Ace> result = new ArrayList<Ace>();
		for(Entry<String, Ace> r : _result.entrySet()){
			result.add(r.getValue());
		}
		return result;
	}

	public void mergeInheritedAces(Acl acl){
		//Inheritすべき外部からのインプットを確定する
		List<Ace> aces = acl.getMergedAces();

		HashMap<String, Ace> localMap = buildMap(localAces);
		HashMap<String, Ace> inheritedMap = buildMap(aces);

		for(Entry<String, Ace> i : inheritedMap.entrySet()){
			if(!localMap.containsKey(i.getKey())){
				this.inheritedAces.add(i.getValue());
			}
		}
	}


	private HashMap<String, Ace> buildMap(List<Ace> aces){
		HashMap<String, Ace> map = new HashMap<String, Ace>();

		for(Ace ace : aces){
			map.put(ace.getPrincipalId(), ace);
		}

		return map;
	}


}
