import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
import server


class CertificateMockTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.old_db = server.DB_FILE
        server.DB_FILE = Path(self.directory.name) / 'mock.db'
        server.initialize_database()
        self.handler = server.Handler.__new__(server.Handler)
        self.response = None
        self.handler._send_codef = self.capture

    def tearDown(self):
        server.DB_FILE = self.old_db
        self.directory.cleanup()

    def capture(self, status, value):
        self.response = value

    def register(self, item):
        self.handler._create_account({'accountList': [item]})
        return self.response['data']['connectedId']

    def test_certificate_registration_and_account_query(self):
        cid = self.register({'loginType': '0', 'organization': '0020',
                             'derFile': 'synthetic-der', 'keyFile': 'synthetic-key', 'password': 'synthetic-cipher'})
        self.assertEqual('0', self.response['data']['successList'][0]['loginType'])
        self.handler._account_list({'connectedId': cid, 'organization': '0020'})
        self.assertEqual(1, len(self.response['data']['resDepositTrust']))
        with server.connect() as db:
            self.assertEqual('demo-user', db.execute('SELECT login_id FROM connections').fetchone()[0])

    def test_certificate_requires_key_material(self):
        with self.assertRaises(server.RequestError):
            self.register({'loginType': '0', 'organization': '0020', 'derFile': 'synthetic-der'})

    def test_id_password_compatibility_and_registered_institution_cleanup(self):
        cid = self.register({'loginType': '1', 'organization': '0020', 'id': 'demo-user'})
        self.assertEqual('1', self.response['data']['successList'][0]['loginType'])
        self.handler._registered_accounts({'connectedId': cid})
        self.assertEqual('0020', self.response['data']['accountList'][0]['organization'])
        self.handler._delete_institutions({'connectedId': cid, 'accountList': [{'organization': '0020'}]})
        self.handler._registered_accounts({'connectedId': cid})
        self.assertEqual([], self.response['data']['accountList'])


class TransactionScenarioMockTest(CertificateMockTest):
    def setUp(self):
        super().setUp()
        self.handler._send_json = self.capture
        self.old_scenario = dict(server.TRANSACTION_SCENARIO)

    def tearDown(self):
        server.TRANSACTION_SCENARIO.update(self.old_scenario)
        super().tearDown()

    def test_delay_and_requested_date_filter(self):
        cid = self.register({'loginType': '1', 'organization': '0020', 'id': 'demo-user'})
        self.handler._set_transaction_scenario({'delayMs': 8000, 'transactions': [
            {'resAccountTrDate': '20261018', 'resAccountIn': '1000'},
            {'resAccountTrDate': '20260930', 'resAccountIn': '2000'},
            {'account': 'different', 'resAccountTrDate': '20261018', 'resAccountIn': '3000'},
        ]})
        with patch('server.time.sleep') as sleep:
            self.handler._transaction_list({'connectedId': cid, 'organization': '0020',
                'account': '1002123456789', 'startDate': '20261001', 'endDate': '20261031'})
        sleep.assert_called_once_with(8)
        self.assertEqual(1, len(self.response['data']['resTrHistoryList']))
        self.assertEqual(0, server.TRANSACTION_COUNTS['inFlight'])

    def test_failure_instead_of_false_empty_success(self):
        cid = self.register({'loginType': '1', 'organization': '0020', 'id': 'demo-user'})
        self.handler._set_transaction_scenario({'httpStatus': 503})
        self.handler._transaction_list({'connectedId': cid, 'organization': '0020',
            'account': '1002123456789', 'startDate': '20261001', 'endDate': '20261031'})
        self.assertNotEqual('CF-00000', self.response['result']['code'])

    def test_invalid_or_unbounded_delay_is_rejected(self):
        for delay in [-1, 120001, '8000']:
            with self.assertRaises(server.RequestError):
                self.handler._set_transaction_scenario({'delayMs': delay})


if __name__ == '__main__':
    unittest.main()
