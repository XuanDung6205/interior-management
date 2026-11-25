import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import tv3_sepay as setup


class SePaySetupTest(unittest.TestCase):
    def config(self,directory,extra=''):
        file=Path(directory)/'private.env'
        file.write_text('SEPAY_ENABLED=true\nSEPAY_API_TOKEN=test-token-private\nSEPAY_WEBHOOK_SECRET=test-hmac-private\nSEPAY_WEBHOOK_ID=00000000-0000-0000-0000-000000000001\n'+extra,encoding='utf-8')
        return file

    def test_inherited_provider_host_and_profile_cannot_override_the_private_environment(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'SEPAY_API_BASE':'https://example.invalid','SEPAY_ENV':'production','SEPAY_PROFILE':'other'}):
            env=setup.private_environment(self.config(directory))
            self.assertNotIn('SEPAY_API_BASE',env)
            self.assertNotIn('SEPAY_ENV',env)
            self.assertNotIn('SEPAY_PROFILE',env)
            self.assertEqual('test-token-private',env['SEPAY_API_TOKEN'])

    def test_unknown_and_duplicate_keys_are_not_executed_or_loaded(self):
        for extra in ['SEPAY_API_BASE=https://example.invalid\n','SEPAY_API_TOKEN=second\n','OTHER=value\n']:
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):setup.private_environment(self.config(directory,extra))

    def test_listener_forwards_to_exact_local_route_and_keeps_secrets_out_of_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            file=self.config(directory)
            with patch.object(sys,'argv',['tool','listen','--config',str(file),'--port','8086']),patch.object(setup,'sepay_cli',return_value='sepay'),patch.object(setup.subprocess,'call',return_value=0) as call:
                self.assertEqual(0,setup.main())
            args=call.call_args.args[0]
            self.assertIn('localhost:8086/api/payments/sepay/webhook',args)
            self.assertIn('sandbox',args)
            self.assertNotIn('test-token-private',' '.join(args))
            self.assertNotIn('test-hmac-private',' '.join(args))


if __name__=='__main__':unittest.main()
