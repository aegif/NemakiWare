#!/usr/bin/env python3
import json
import re
import uuid
import sys
import os

def generate_uuid():
    """Generate a UUID v4 string"""
    return str(uuid.uuid4())

def is_systematic_id(value):
    """Check if a value looks like a systematic ID"""
    if not isinstance(value, str):
        return False
    
    # Pattern 1: Contains many zeros followed by small numbers
    if re.search(r'[a-zA-Z]+0{15,}\d{1,5}$', value):
        return True
    
    # Pattern 2: Contains 'user', 'group', 'system', 'admin' etc. with many zeros
    if re.search(r'(admin|user|group|system|folder|prop|type|relationship|solr).{0,10}0{10,}', value):
        return True
        
    # Pattern 3: Very long strings with mostly zeros
    if len(value) > 25 and value.count('0') > len(value) * 0.6:
        return True
    
    return False

def find_all_systematic_ids(dump_file_path):
    """Find all systematic IDs in a dump file"""
    systematic_ids = set()
    
    try:
        with open(dump_file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            
        # Parse the JSON array
        try:
            data = json.loads(content)
        except json.JSONDecodeError:
            print(f"Warning: Could not parse {dump_file_path} as JSON array, trying line by line...")
            # Try parsing line by line for malformed JSON
            lines = content.strip().split('\n')
            for line in lines:
                if line.strip():
                    try:
                        obj = json.loads(line)
                        extract_ids_from_object(obj, systematic_ids)
                    except:
                        continue
            return systematic_ids
        
        # Extract IDs from properly parsed JSON
        if isinstance(data, list):
            for item in data:
                extract_ids_from_object(item, systematic_ids)
        else:
            extract_ids_from_object(data, systematic_ids)
            
    except Exception as e:
        print(f"Error reading {dump_file_path}: {e}")
        
    return systematic_ids

def extract_ids_from_object(obj, systematic_ids):
    """Recursively extract systematic IDs from an object"""
    if isinstance(obj, dict):
        for key, value in obj.items():
            if isinstance(value, str) and is_systematic_id(value):
                systematic_ids.add(value)
            elif isinstance(value, (dict, list)):
                extract_ids_from_object(value, systematic_ids)
    elif isinstance(obj, list):
        for item in obj:
            extract_ids_from_object(item, systematic_ids)

def create_id_mapping(systematic_ids):
    """Create a mapping from systematic IDs to UUIDs"""
    mapping = {}
    for sys_id in systematic_ids:
        mapping[sys_id] = generate_uuid()
    return mapping

def replace_ids_in_value(value, id_mapping):
    """Replace systematic IDs in a string value"""
    if not isinstance(value, str):
        return value
    
    result = value
    for old_id, new_id in id_mapping.items():
        if old_id in result:
            result = result.replace(old_id, new_id)
    
    return result

def replace_ids_in_object(obj, id_mapping):
    """Recursively replace systematic IDs in an object"""
    if isinstance(obj, dict):
        result = {}
        for key, value in obj.items():
            if isinstance(value, str):
                result[key] = replace_ids_in_value(value, id_mapping)
            elif isinstance(value, (dict, list)):
                result[key] = replace_ids_in_object(value, id_mapping)
            else:
                result[key] = value
        return result
    elif isinstance(obj, list):
        return [replace_ids_in_object(item, id_mapping) for item in obj]
    else:
        return obj

def convert_dump_file(input_path, output_path, id_mapping):
    """Convert a dump file by replacing systematic IDs with UUIDs"""
    try:
        with open(input_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Parse and convert the JSON
        try:
            data = json.loads(content)
            converted_data = replace_ids_in_object(data, id_mapping)
            
            # Write the converted data
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(converted_data, f, indent=2, ensure_ascii=False)
                
        except json.JSONDecodeError:
            print(f"Warning: Malformed JSON in {input_path}, processing line by line...")
            lines = content.strip().split('\n')
            converted_lines = []
            
            for line in lines:
                if line.strip():
                    try:
                        obj = json.loads(line)
                        converted_obj = replace_ids_in_object(obj, id_mapping)
                        converted_lines.append(json.dumps(converted_obj, ensure_ascii=False))
                    except:
                        # Keep original line if can't parse
                        converted_lines.append(line)
                else:
                    converted_lines.append(line)
            
            with open(output_path, 'w', encoding='utf-8') as f:
                f.write('\n'.join(converted_lines))
        
        print(f"Successfully converted {input_path} -> {output_path}")
        
    except Exception as e:
        print(f"Error converting {input_path}: {e}")

def main():
    # Define file paths
    base_dir = "/Users/ishiiakinori/NemakiWare/setup/couchdb/initial_import"
    bedroom_input = os.path.join(base_dir, "bedroom_init.dump")
    canopy_input = os.path.join(base_dir, "canopy_init.dump")
    
    bedroom_output = os.path.join(base_dir, "bedroom_init_uuid.dump")
    canopy_output = os.path.join(base_dir, "canopy_init_uuid.dump")
    mapping_output = os.path.join(base_dir, "id_mapping.json")
    
    print("=== NemakiWare Systematic ID to UUID Converter ===")
    
    # Step 1: Find all systematic IDs
    print("Step 1: Analyzing systematic IDs...")
    bedroom_ids = find_all_systematic_ids(bedroom_input)
    canopy_ids = find_all_systematic_ids(canopy_input)
    
    all_systematic_ids = bedroom_ids.union(canopy_ids)
    print(f"Found {len(all_systematic_ids)} unique systematic IDs")
    
    # Print some examples
    print("Examples of systematic IDs found:")
    for i, sys_id in enumerate(sorted(all_systematic_ids)):
        if i < 10:
            print(f"  - {sys_id}")
        elif i == 10:
            print(f"  ... and {len(all_systematic_ids) - 10} more")
            break
    
    # Step 2: Create ID mapping
    print("\nStep 2: Creating UUID mapping...")
    id_mapping = create_id_mapping(all_systematic_ids)
    
    # Step 3: Convert files
    print("\nStep 3: Converting dump files...")
    convert_dump_file(bedroom_input, bedroom_output, id_mapping)
    convert_dump_file(canopy_input, canopy_output, id_mapping)
    
    # Step 4: Save mapping
    print("\nStep 4: Saving ID mapping...")
    with open(mapping_output, 'w', encoding='utf-8') as f:
        json.dump(id_mapping, f, indent=2, ensure_ascii=False)
    
    print(f"\nConversion completed successfully!")
    print(f"Output files:")
    print(f"  - {bedroom_output}")
    print(f"  - {canopy_output}")
    print(f"  - {mapping_output}")
    
    # Show some mapping examples
    print(f"\nExample ID mappings:")
    for i, (old_id, new_id) in enumerate(id_mapping.items()):
        if i < 5:
            print(f"  {old_id} -> {new_id}")
        elif i == 5:
            print(f"  ... and {len(id_mapping) - 5} more mappings")
            break

if __name__ == "__main__":
    main()