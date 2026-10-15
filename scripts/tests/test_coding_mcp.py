import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'app/src/main/assets/coding/workspace_mcp.py'
spec = importlib.util.spec_from_file_location('workspace_mcp', SCRIPT)
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)

class CodingMcpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.project = self.root / 'app'
        self.project.mkdir()
        (self.project / 'package.json').write_text('{}')
        (self.project / 'main.py').write_text('print("سلام")\n# needle\n')
    def tearDown(self):
        self.temp.cleanup()
    def test_real_rpc_handshake_and_tool_call(self):
        requests = [
            {'jsonrpc':'2.0', 'id':1, 'method':'initialize'},
            {'jsonrpc':'2.0', 'method':'notifications/initialized'},
            {'jsonrpc':'2.0', 'id':2, 'method':'tools/list'},
            {'jsonrpc':'2.0', 'id':3, 'method':'tools/call', 'params':{'name':'search_code', 'arguments':{'project':'app', 'query':'سلام'}}},
        ]
        process = subprocess.run([sys.executable, str(SCRIPT), '--root', str(self.root)], input='\n'.join(map(json.dumps, requests))+'\n', capture_output=True, text=True, timeout=5, check=True)
        replies = list(map(json.loads, process.stdout.splitlines()))
        self.assertEqual([1,2,3], [r['id'] for r in replies])
        self.assertEqual('2025-06-18', replies[0]['result']['protocolVersion'])
        self.assertEqual(3, len(replies[1]['result']['tools']))
        self.assertEqual('main.py', json.loads(replies[2]['result']['content'][0]['text'])['matches'][0]['file'])
    def test_overview_ignores_dependencies_and_hidden_secrets(self):
        (self.project / '.env').write_text('SECRET=needle')
        deps = self.project / 'node_modules'; deps.mkdir(); (deps / 'big.js').write_text('needle')
        overview = mcp.call(self.root, 'project_overview', {'project':'app'})
        self.assertEqual(['main.py','package.json'], overview['files'])
        self.assertEqual(['package.json'], overview['manifests'])
    def test_path_and_symlink_escape_are_rejected(self):
        (self.project / 'escape').symlink_to(self.root.parent, target_is_directory=True)
        for path in ('../', 'app/escape'):
            with self.assertRaises(ValueError): mcp.call(self.root, 'project_overview', {'project':path})
    def test_search_is_literal_and_results_are_bounded(self):
        (self.project / 'matches.txt').write_text(('literal.* needle\n') * 100)
        result = mcp.call(self.root, 'search_code', {'project':'app', 'query':'literal.*'})
        self.assertEqual(60, len(result['matches']))
        self.assertTrue(result['truncated'])
    def test_git_reports_staged_and_unstaged_without_changing_files(self):
        subprocess.run(['git','init','-q',str(self.project)], check=True)
        subprocess.run(['git','-C',str(self.project),'add','main.py'], check=True)
        before = (self.project / 'main.py').read_text()
        result = mcp.call(self.root, 'git_changes', {'project':'app'})
        self.assertIn('A  main.py', result['status'])
        self.assertIn('+# needle', result['staged_diff'])
        self.assertEqual(before, (self.project / 'main.py').read_text())
    def test_large_git_output_is_bounded(self):
        subprocess.run(['git','init','-q',str(self.project)], check=True)
        (self.project / 'large.txt').write_text('large line\n' * 10000)
        subprocess.run(['git','-C',str(self.project),'add','.'], check=True)
        result = mcp.call(self.root, 'git_changes', {'project':'app'})
        self.assertIn('[output truncated]', result['staged_diff'])
        self.assertLess(len(result['staged_diff']), 24100)
    def test_tool_errors_do_not_kill_server(self):
        requests = [{'id':1,'method':'tools/call','params':{'name':'project_overview','arguments':{'project':'../'}}}, {'id':2,'method':'ping'}]
        result = subprocess.run([sys.executable,str(SCRIPT),'--root',str(self.root)], input='\n'.join(map(json.dumps,requests))+'\n', capture_output=True,text=True,check=True,timeout=5)
        replies = list(map(json.loads,result.stdout.splitlines()))
        self.assertTrue(replies[0]['result']['isError'])
        self.assertEqual({},replies[1]['result'])

if __name__ == '__main__': unittest.main()
