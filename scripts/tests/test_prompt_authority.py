import pathlib,unittest
class PromptAuthorityTests(unittest.TestCase):
 def test_prompt_does_not_claim_blanket_device_permission(self):
  source=(pathlib.Path(__file__).resolve().parents[2]/'app/src/main/java/com/ersaz/hermesmobile/agent/Agent.kt').read_text()
  self.assertNotIn('granted you FULL permission',source)
if __name__=='__main__':unittest.main()
