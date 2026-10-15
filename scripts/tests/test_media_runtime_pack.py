import importlib.util,pathlib,tempfile,tarfile,unittest
p=pathlib.Path(__file__).resolve().parents[1]/'media_runtime_pack.py'
s=importlib.util.spec_from_file_location('pack',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class PackTests(unittest.TestCase):
 def test_delta_excludes_existing_files_and_package_database(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)/'root';(root/'usr/bin').mkdir(parents=True);(root/'usr/bin/python3').write_text('base')
   before=m.collect(root)
   (root/'usr/bin/ffmpeg').write_text('new');(root/'lib/apk/db').mkdir(parents=True);(root/'lib/apk/db/installed').write_text('never overwrite phone database')
   archive=pathlib.Path(d)/'media.bin';m.write_delta(before,root,archive)
   with tarfile.open(archive,'r:gz') as t:self.assertEqual(['usr/bin/ffmpeg'],t.getnames())
if __name__=='__main__':unittest.main()
