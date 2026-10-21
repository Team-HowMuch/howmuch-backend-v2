"""Recipe persistence regression checks; no DB, credentials or network access."""
import contextlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('booth_actions', Path(__file__).with_name('booth-actions.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Rejected(Exception):
    pass


class RecipeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        class Console:
            manifest = {'actors': [{'user_id': n, 'name': str(n)} for n in [85, 86, 87, 88]]}
            baseline = {'settlements': []}
            lock = staticmethod(contextlib.nullcontext)
        class Engine:
            @staticmethod
            def private_json(path, value):
                path.write_text(json.dumps(value))
        def reject(*args):
            raise Rejected(args)
        console = Console()
        console.directory = Path(self.temp.name)
        self.booth = module.Booth(console, Engine(), reject)
        self.path = console.directory / 'booth-templates.json'
        self.body = dict(name='정기 모임', groupName='모임', ownerId=86, members=[85, 86],
                         settlements=[dict(title='이용권', amount=1000, creatorId=86,
                                           participants=[85, 86], daysAgo=3, recurring=True)])

    def test_edit_replaces_same_recipe_and_delete_removes_it(self):
        saved = self.booth.save(self.body)
        self.booth.save(dict(self.body, id=saved['id'], name='수정된 모임'))
        matches = [r for r in self.booth.recipes() if r['id'] == saved['id']]
        self.assertEqual([r['name'] for r in matches], ['수정된 모임'])
        self.booth.save(dict(id=saved['id'], action='delete'))
        self.assertNotIn(saved['id'], [r['id'] for r in self.booth.recipes()])

    def test_default_override_and_hide_survive_reload(self):
        self.booth.save(dict(self.body, id='compression'))
        self.assertEqual(self.booth.recipes()[0]['name'], self.body['name'])
        self.booth.save(dict(id='compression', action='delete'))
        self.assertNotIn('compression', [r['id'] for r in self.booth.recipes()])
        self.assertIn('compression', json.loads(self.path.read_text())['hidden'])

    def test_legacy_list_is_preserved_on_first_write(self):
        self.path.write_text(json.dumps([dict(self.body, id='legacy')]))
        self.booth.save(self.body)
        self.assertIn('legacy', [r['id'] for r in self.booth.recipes()])

    def test_unknown_id_and_action_leave_store_unchanged(self):
        for body in [dict(id='unknown', action='delete'), dict(self.body, action='other')]:
            with self.assertRaises(Rejected):
                self.booth.save(body)
        self.assertFalse(self.path.exists())

    def test_invalid_participants_are_rejected_before_persistence(self):
        bad = dict(self.body, settlements=[dict(self.body['settlements'][0], participants=[85, 87])])
        with self.assertRaises(Rejected):
            self.booth.save(bad)
        self.assertFalse(self.path.exists())


if __name__ == '__main__':
    unittest.main()
