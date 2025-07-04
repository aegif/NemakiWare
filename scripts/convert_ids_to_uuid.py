#!/usr/bin/env python3
"""
Convert systematic IDs to UUIDs in NemakiWare init dump files.

This script reads bedroom_init.dump and canopy_init.dump files,
identifies systematic ID patterns, replaces them with proper UUID v4 format,
and maintains consistency across all references.
"""

import json
import re
import uuid
import sys
from pathlib import Path
from collections import OrderedDict
from typing import Dict, Set, Any, List, Tuple


class IdConverter:
    """Handles conversion of systematic IDs to UUIDs."""
    
    def __init__(self):
        # Mapping of old IDs to new UUIDs
        self.id_mapping: Dict[str, str] = {}
        
        # Patterns for systematic IDs
        self.id_patterns = [
            # General patterns
            r'^[a-z]+[0-9]{25,32}$',  # e.g., adminuser00000000000000000000000
            r'^[a-z]+_[a-z]+[0-9]{25,32}$',  # e.g., root_folder00000000000000000000
            
            # Specific patterns for different types
            r'^adminuser[0-9]+$',
            r'^everyonegroup[0-9]+$',
            r'^groupfolder[0-9]+$',
            r'^system[0-9]+$',
            r'^userfolder[0-9]+$',
            r'^groupcanopy[0-9]+$',
            r'^systemcanopy[0-9]+$',
            r'^usercanopy[0-9]+$',
            r'^rootfolder[0-9]+$',
            r'^root_folder[0-9]+$',
            r'^testuser[0-9]+$',
            r'^testgroup[0-9]+$',
            r'^repositoryinfo[0-9]+$',
            
            # Also match IDs with specific hex patterns
            r'^[a-f0-9]{32}$',  # 32-char hex strings that look systematic
        ]
        
        # Fields that might contain ID references
        self.id_fields = {
            '_id', 'id', 'parentId', 'objectId', 'principalId', 
            'changeToken', 'userId', 'groupId', 'folderId',
            'contentStreamId', 'latestVersionId', 'versionSeriesId',
            'baseId', 'policyId', 'sourceId', 'targetId',
            'createdBy', 'modifiedBy', 'checkedOutBy', 'owner',
            'root', 'archive', 'repositoryId'
        }
        
        # Fields that might contain ID references in arrays
        self.array_id_fields = {
            'parents', 'members', 'groups', 'policies',
            'allowedChildObjectTypeIds', 'rootFolderIds'
        }
        
        # Special handling for certain IDs that should remain unchanged
        self.preserve_ids = {
            'bedroom', 'canopy', 'bedroom_closet', 'canopy_closet',
            'nemaki:user', 'nemaki:group', 'cmis:folder', 'cmis:document'
        }

    def is_systematic_id(self, value: str) -> bool:
        """Check if a value matches systematic ID patterns."""
        if not isinstance(value, str):
            return False
            
        # Skip IDs that should be preserved
        if value in self.preserve_ids:
            return False
            
        # Skip short strings or very long strings
        if len(value) < 20 or len(value) > 40:
            return False
            
        # Check against patterns
        for pattern in self.id_patterns:
            if re.match(pattern, value):
                return True
                
        # Additional check for IDs that look like systematic patterns
        # but might not match the regex exactly
        if self._looks_systematic(value):
            return True
            
        return False

    def _looks_systematic(self, value: str) -> bool:
        """Additional heuristic to identify systematic IDs."""
        # Check for patterns like repeated zeros or sequential numbers
        if '00000000' in value:
            return True
            
        # Check for IDs that are all lowercase with numbers at the end
        if re.match(r'^[a-z_]+[0-9]{10,}$', value):
            return True
            
        return False

    def generate_uuid(self) -> str:
        """Generate a proper UUID v4 in standard format."""
        return str(uuid.uuid4())

    def get_or_create_uuid(self, old_id: str) -> str:
        """Get existing UUID mapping or create a new one."""
        if old_id not in self.id_mapping:
            self.id_mapping[old_id] = self.generate_uuid()
        return self.id_mapping[old_id]

    def convert_value(self, value: Any) -> Any:
        """Convert a single value, replacing systematic IDs with UUIDs."""
        if isinstance(value, str) and self.is_systematic_id(value):
            return self.get_or_create_uuid(value)
        elif isinstance(value, dict):
            return self.convert_dict(value)
        elif isinstance(value, list):
            return self.convert_list(value)
        else:
            return value

    def convert_dict(self, obj: Dict[str, Any]) -> Dict[str, Any]:
        """Recursively convert all systematic IDs in a dictionary."""
        result = {}
        
        for key, value in obj.items():
            # Check if this field might contain an ID
            if key in self.id_fields:
                result[key] = self.convert_value(value)
            elif key in self.array_id_fields and isinstance(value, list):
                result[key] = self.convert_list(value)
            elif isinstance(value, dict):
                result[key] = self.convert_dict(value)
            elif isinstance(value, list):
                result[key] = self.convert_list(value)
            else:
                # Check for string values that might be IDs
                result[key] = self.convert_value(value)
                
        return result

    def convert_list(self, lst: List[Any]) -> List[Any]:
        """Convert all systematic IDs in a list."""
        return [self.convert_value(item) for item in lst]

    def process_dump_file(self, input_path: Path, output_path: Path) -> None:
        """Process a single dump file."""
        print(f"\nProcessing {input_path.name}...")
        
        # Read the dump file
        with open(input_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        # Track conversion statistics
        total_documents = 0
        total_conversions = 0
        
        # Process each line (each line is a JSON document)
        converted_lines = []
        for i, line in enumerate(lines):
            line = line.strip()
            if not line:
                converted_lines.append('')
                continue
                
            try:
                # Parse the JSON document
                doc = json.loads(line)
                total_documents += 1
                
                # Count IDs before conversion
                before_count = len(self.id_mapping)
                
                # Convert the document
                converted_doc = self.convert_dict(doc)
                
                # Count IDs after conversion
                after_count = len(self.id_mapping)
                if after_count > before_count:
                    total_conversions += after_count - before_count
                
                # Convert back to JSON
                converted_line = json.dumps(converted_doc, separators=(',', ':'), 
                                          ensure_ascii=False)
                converted_lines.append(converted_line)
                
            except json.JSONDecodeError as e:
                print(f"  Warning: Failed to parse line {i+1}: {e}")
                converted_lines.append(line)  # Keep original if parsing fails
            except Exception as e:
                print(f"  Error processing line {i+1}: {e}")
                converted_lines.append(line)  # Keep original on error
        
        # Write the converted dump file
        with open(output_path, 'w', encoding='utf-8') as f:
            for line in converted_lines:
                if line:  # Only write non-empty lines
                    f.write(line + '\n')
        
        print(f"  Processed {total_documents} documents")
        print(f"  Converted {total_conversions} systematic IDs")
        print(f"  Total unique IDs mapped: {len(self.id_mapping)}")

    def save_mapping(self, output_path: Path) -> None:
        """Save the ID mapping to a file for reference."""
        # Sort the mapping for readability
        sorted_mapping = OrderedDict(sorted(self.id_mapping.items()))
        
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(sorted_mapping, f, indent=2, ensure_ascii=False)
        
        print(f"\nID mapping saved to {output_path}")
        print(f"Total mappings: {len(self.id_mapping)}")

    def analyze_dump_file(self, file_path: Path) -> Dict[str, Set[str]]:
        """Analyze a dump file to find all systematic IDs."""
        print(f"\nAnalyzing {file_path.name}...")
        
        systematic_ids: Dict[str, Set[str]] = {
            'document_ids': set(),
            'referenced_ids': set()
        }
        
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        for line in lines:
            line = line.strip()
            if not line:
                continue
                
            try:
                doc = json.loads(line)
                
                # Find systematic IDs in this document
                self._find_systematic_ids(doc, systematic_ids)
                
            except json.JSONDecodeError:
                continue
        
        return systematic_ids

    def _find_systematic_ids(self, obj: Any, result: Dict[str, Set[str]], 
                           is_id_field: bool = False) -> None:
        """Recursively find systematic IDs in an object."""
        if isinstance(obj, dict):
            for key, value in obj.items():
                is_id = key in self.id_fields or is_id_field
                self._find_systematic_ids(value, result, is_id)
                
        elif isinstance(obj, list):
            for item in obj:
                self._find_systematic_ids(item, result, is_id_field)
                
        elif isinstance(obj, str) and self.is_systematic_id(obj):
            if is_id_field:
                result['referenced_ids'].add(obj)
            else:
                result['document_ids'].add(obj)


def main():
    """Main function to process dump files."""
    # Define paths
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    
    # Input paths
    bedroom_input = project_root / 'setup' / 'couchdb' / 'initial_import' / 'bedroom_init.dump'
    canopy_input = project_root / 'setup' / 'couchdb' / 'initial_import' / 'canopy_init.dump'
    
    # Output paths
    output_dir = project_root / 'setup' / 'couchdb' / 'initial_import' / 'uuid_converted'
    output_dir.mkdir(exist_ok=True)
    
    bedroom_output = output_dir / 'bedroom_init_uuid.dump'
    canopy_output = output_dir / 'canopy_init_uuid.dump'
    mapping_output = output_dir / 'id_mapping.json'
    
    # Check if input files exist
    if not bedroom_input.exists():
        print(f"Error: {bedroom_input} not found!")
        sys.exit(1)
    if not canopy_input.exists():
        print(f"Error: {canopy_input} not found!")
        sys.exit(1)
    
    print("UUID Conversion Tool for NemakiWare Init Dumps")
    print("=" * 50)
    
    # Create converter instance
    converter = IdConverter()
    
    # Analyze files first (optional, for information)
    print("\nPhase 1: Analyzing dump files for systematic IDs...")
    bedroom_ids = converter.analyze_dump_file(bedroom_input)
    canopy_ids = converter.analyze_dump_file(canopy_input)
    
    print(f"\nBedroom dump analysis:")
    print(f"  Document IDs: {len(bedroom_ids['document_ids'])}")
    print(f"  Referenced IDs: {len(bedroom_ids['referenced_ids'])}")
    
    print(f"\nCanopy dump analysis:")
    print(f"  Document IDs: {len(canopy_ids['document_ids'])}")
    print(f"  Referenced IDs: {len(canopy_ids['referenced_ids'])}")
    
    # Process the dump files
    print("\nPhase 2: Converting systematic IDs to UUIDs...")
    
    # Process bedroom first
    converter.process_dump_file(bedroom_input, bedroom_output)
    
    # Process canopy (will reuse mappings for any shared IDs)
    converter.process_dump_file(canopy_input, canopy_output)
    
    # Save the mapping
    converter.save_mapping(mapping_output)
    
    print("\nConversion complete!")
    print(f"\nOutput files:")
    print(f"  - {bedroom_output}")
    print(f"  - {canopy_output}")
    print(f"  - {mapping_output}")
    
    # Show some example mappings
    print("\nExample ID mappings:")
    examples = list(converter.id_mapping.items())[:5]
    for old_id, new_id in examples:
        print(f"  {old_id} -> {new_id}")
    
    if len(converter.id_mapping) > 5:
        print(f"  ... and {len(converter.id_mapping) - 5} more")


if __name__ == "__main__":
    main()