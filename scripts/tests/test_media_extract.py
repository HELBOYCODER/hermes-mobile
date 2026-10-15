import importlib.util, pathlib, tempfile, unittest
PATH=pathlib.Path(__file__).resolve().parents[2]/'app/src/main/assets/media/extract_media.py'
spec=importlib.util.spec_from_file_location('hermes_media',PATH)
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
class MediaTests(unittest.TestCase):
 def test_captions_are_attributed_and_metadata_is_not_a_transcript(self):
  with tempfile.TemporaryDirectory() as d:
   r=m.extract('https://youtu.be/abcdefghijk',d,caption_fetcher=lambda _: [{'start':0,'duration':2,'text':'سلام دنیا'}],info_fetcher=lambda _: {'title':'Demo','duration':10})
   self.assertEqual('captions',r['coverage']); self.assertEqual('سلام دنیا',r['segments'][0]['text'])
   self.assertEqual('https://www.youtube.com/watch?v=abcdefghijk',r['source_url'])
   r=m.extract('https://www.instagram.com/reel/AbC123/',d,caption_fetcher=lambda _: [],info_fetcher=lambda _: {'title':'Demo','description':'caption','duration':10})
   self.assertEqual('metadata',r['coverage']);self.assertEqual([],r['segments'])
 def test_rejects_other_hosts_and_long_media(self):
  with tempfile.TemporaryDirectory() as d:
   for u in ['https://youtube.com.evil.test/?v=abcdefghijk','http://127.0.0.1/','https://youtu.be/no','https://user:pass@youtu.be/abcdefghijk']:
    with self.assertRaises(m.MediaError): m.extract(u,d)
   with self.assertRaises(m.MediaError): m.extract('https://youtu.be/abcdefghijk',d,caption_fetcher=lambda _:[],info_fetcher=lambda _:{'duration':1201})
 def test_download_budget_stops_unknown_length_stream(self):
  hook=m.download_guard(100)
  with self.assertRaises(m.MediaError): hook({'downloaded_bytes':101})
  hook({'downloaded_bytes':100})
if __name__=='__main__':unittest.main()
