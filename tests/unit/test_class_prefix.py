import ncf 
import codecs
import re
import unittest

def custom_gm_metadata(alt_path = ''):
  all_metadata = {}
  filenames = ncf.get_all_generic_methods_filenames(alt_path)

  for file in filenames:
    with codecs.open(file, encoding="utf-8") as fd:
      content = fd.read()
    try:
      result = ncf.parse_generic_method_metadata(content)
      metadata = result["result"]
      metadata["filename"] = file
      all_metadata[metadata['bundle_name']] = metadata
    except NcfError as e:
      print("Could not parse generic method in '" + file + "'")
      continue # skip this file, it doesn't have the right tags in - yuk!
  return all_metadata

def test_pattern_on_file(filename, pattern):
  with codecs.open(filename, encoding="utf-8") as fd:
    content = fd.read()
    return re.search(pattern, content)

def should_ignore(filename):
  toSkip = ["file_from_shared_folder", "user_password_hash"]
  reason = ""
  result = False
  if test_pattern_on_file(filename, r"{\s+methods:\s+[^;]+;\s+}"):
    reason = "not a true generic method"
    result = True
  elif any(skip in filename for skip in toSkip):
    reason = "skipped for a good reason"
    result = True
  if result:
    print("Skipping %s, %s"%(filename, reason))
  return result

class TestClassPrefix(unittest.TestCase):
  def setUp(self):
    self.metadata = custom_gm_metadata()
      
  def test_class_prefix(self):
    test_result = 0
    for k in self.metadata:
      with self.subTest(k=k):
        params = ",\s*".join(["\"\${" + x.strip() + "}\"" for x in self.metadata[k]['bundle_args']])
        class_prefix_pattern = "\s+\"args\"\s+slist\s+=>\s+\{\s*" + params + "\s*\};"
        filename = self.metadata[k]["filename"]
        if not should_ignore(filename):
          self.assertTrue(test_pattern_on_file(filename, class_prefix_pattern) != None)
    
  def test_old_class_prefix(self):
    test_result = 0
    for k in self.metadata:
      with self.subTest(k=k):
        filename = self.metadata[k]["filename"]
        class_prefix = self.metadata[k]["class_prefix"]
        class_parameter = self.metadata[k]["class_parameter"]
  
        class_pattern1 = "\"old_class_prefix\"\s+string\s+=>\s+canonify\(\"" + class_prefix + "_" + "\${" + class_parameter + "}\"\);"
        class_pattern2 = "\"old_class_prefix\"\s+string\s+=>\s+\"" + class_prefix + "_" + "\${canonified_" + class_parameter + "}\";"
  
        if not should_ignore(filename):
          self.assertTrue(test_pattern_on_file(filename, class_pattern1) != None or test_pattern_on_file(filename, class_pattern2) != None)

if __name__ == '__main__':
  unittest.main()
