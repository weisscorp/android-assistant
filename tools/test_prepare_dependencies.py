"""Synthetic archives; no network, Android device or voice capture."""
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile
from prepare_dependencies import install, member_path


class DependencyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def archive(self, entries, fmt='source-tar', **extra):
        path = self.root / 'source.tar.gz'
        with tarfile.open(path, 'w:gz') as target:
            for name, data in entries:
                item = tarfile.TarInfo(name)
                if data is None:
                    item.type = tarfile.SYMTYPE
                    item.linkname = '/outside'
                    target.addfile(item)
                else:
                    item.size = len(data)
                    target.addfile(item, io.BytesIO(data))
        return path, dict(id='synthetic', format=fmt, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), **extra)

    def test_source_install_is_atomic_and_preserves_developer_destination(self):
        archive, spec = self.archive([('source/LICENSE', b'synthetic notice'), ('source/lib/code.c', b'code')])
        output = self.root / 'vendor/source'
        install(archive, output, spec)
        self.assertEqual((output / 'lib/code.c').read_bytes(), b'code')
        (output / 'lib/code.c').write_bytes(b'developer edit')
        install(archive, output, spec)
        self.assertEqual((output / 'lib/code.c').read_bytes(), b'developer edit')
        (output / '.assistant-source.json').unlink()
        with self.assertRaises(ValueError):
            install(archive, output, spec)

    def test_bad_checksum_does_not_replace_existing_file(self):
        archive, spec = self.archive([('root/file', b'x')], fmt='file')
        output = self.root / 'existing'
        output.write_bytes(b'previous')
        spec['sha256'] = '0' * 64
        with self.assertRaises(ValueError):
            install(archive, output, spec)
        self.assertEqual(output.read_bytes(), b'previous')

    def test_traversal_links_and_duplicate_members_are_refused(self):
        for members in ([('root/../../outside', b'x')], [('/root/absolute', b'x')],
                        [('root/link', None)], [('root/file', b'1'), ('root/file', b'2')]):
            archive, spec = self.archive(members)
            output = self.root / 'vendor/bad'
            with self.assertRaises(ValueError):
                install(archive, output, spec)
            self.assertFalse(output.exists())
        self.assertFalse((self.root / 'outside').exists())

    def test_selected_model_members_are_verified_and_notice_preserved(self):
        expected = {'model.onnx': hashlib.sha256(b'model').hexdigest(), 'LICENSE': hashlib.sha256(b'notice').hexdigest()}
        archive, spec = self.archive([('root/model.onnx', b'model'), ('root/LICENSE', b'notice'), ('root/sample.wav', b'audio')], fmt='model-tar', members=expected)
        output = self.root / 'assets/model.zip'
        install(archive, output, spec)
        with zipfile.ZipFile(output) as result:
            self.assertEqual(set(result.namelist()), {'root/model.onnx', 'root/LICENSE'})
            self.assertEqual(result.read('root/LICENSE'), b'notice')

    def test_missing_member_with_extra_unselected_file_fails_atomically(self):
        archive, spec = self.archive([('root/model.onnx', b'model'), ('root/extra', b'other')], fmt='model-tar',
                                     members={'model.onnx': hashlib.sha256(b'model').hexdigest(), 'LICENSE': '0' * 64})
        output = self.root / 'model.zip'
        output.write_bytes(b'previous')
        with self.assertRaises(ValueError):
            install(archive, output, spec)
        self.assertEqual(output.read_bytes(), b'previous')

    def test_repacked_voice_preserves_directory_layout(self):
        archive, spec = self.archive([('voice/model.onnx', b'model'), ('voice/espeak-ng-data/file', b'data'),
                                     ('voice/MODEL_CARD', b'notice')], fmt='model-tar')
        output = self.root / 'model.zip'
        install(archive, output, spec)
        with zipfile.ZipFile(output) as result:
            self.assertIn('voice/espeak-ng-data/file', result.namelist())

    def test_symlink_output_and_unsafe_manifest_path_are_refused(self):
        archive, spec = self.archive([('root/file', b'x')], fmt='file')
        output = self.root / 'link'
        output.symlink_to(self.root / 'target')
        with self.assertRaises(ValueError):
            install(archive, output, spec)
        for path in ('/outside', '../outside', 'root/../outside', 'root\\outside'):
            with self.assertRaises(ValueError):
                member_path(path)


if __name__ == '__main__':
    unittest.main()
