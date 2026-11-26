import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts'))
import tv3_vector as setup

class VectorSetupTest(unittest.TestCase):
    def config(self,directory,extra=''):
        file=Path(directory)/'private.env'
        file.write_text('GEMINI_API_KEY=fixture-key-private\nVECTOR_ENABLED=true\n'+extra,encoding='utf-8')
        return file

    def test_private_environment_rejects_unknown_duplicate_and_control_values(self):
        for extra in ['VECTOR_BASE_URL=https://example.invalid\n','GEMINI_API_KEY=another\n','JAVA_TOOL_OPTIONS=bad\n','VECTOR_ENABLED=bad\x00value\n']:
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):setup.private_environment(self.config(directory,extra))

    def test_private_configuration_wins_over_inherited_vector_settings(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'VECTOR_ENABLED':'false','VECTOR_BASE_URL':'https://example.invalid'}):
            env=setup.private_environment(self.config(directory))
            self.assertEqual('true',env['VECTOR_ENABLED']);self.assertNotIn('VECTOR_BASE_URL',env)

    def test_run_keeps_key_in_child_environment_and_never_in_arguments_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            file=self.config(directory)
            with patch.object(sys,'argv',['tool','run','--config',str(file),'--database','furniture_shop_test_vector_unit']),patch.object(setup.tv3_release,'prepare_run',return_value=(['java','-jar','demo.jar'],{})),patch.object(setup.subprocess,'call',return_value=0) as call,contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0,setup.main())
            self.assertEqual('fixture-key-private',call.call_args.kwargs['env']['GEMINI_API_KEY'])
            self.assertNotIn('fixture-key-private',' '.join(call.call_args.args[0]))
            self.assertNotIn('fixture-key-private',output.getvalue())

    def test_provider_check_requires_exact_finite_nonzero_768_vector(self):
        self.assertTrue(setup.valid_embedding([1]+[0]*767))
        for values in [[1]*767,[0]*768,[float('nan')]+[0]*767,[10**400]+[0]*767,[True]+[0]*767,['1']*768]:self.assertFalse(setup.valid_embedding(values))

if __name__=='__main__':unittest.main()
