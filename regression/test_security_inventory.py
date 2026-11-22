import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

from scripts.security_inventory import browser_inventory, maven_components


class SecurityInventoryTests(unittest.TestCase):
    def test_standalone_lite_browser_inventory_allows_unbundled_xmldom(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / 'viewer.zip'
            output = root / 'viewer.cdx.json'
            inventory = {'components': [{'name': 'PDF.js', 'version': '1.2.3'}],
                         'metadata': {'component': {}}}
            for profile in ('even-more-lite', 'full'):
                with self.subTest(profile=profile):
                    with ZipFile(package, 'w') as archive:
                        archive.writestr('SBOM.cdx.json', json.dumps(inventory))
                        archive.writestr('build-info.json', json.dumps({'profile': profile}))
                    if profile == 'full':
                        with self.assertRaisesRegex(ValueError, 'Missing browser runtime components'):
                            browser_inventory(output, package)
                    else:
                        browser_inventory(output, package)
                        component = json.loads(output.read_text())['components'][0]
                        self.assertEqual('pkg:npm/pdfjs-dist@1.2.3', component['purl'])

    def test_maven_annotations_preserve_artifact_path_and_classifier(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'artifacts -- module cache' / 'sample-1.0-data.jar'
            jar.parent.mkdir()
            with ZipFile(jar, 'w'):
                pass
            dependencies = root / 'dependencies.txt'
            line = f'example:sample:jar:data:1.0:runtime:{jar}'
            dependencies.write_text(line + '\n')
            expected = maven_components(dependencies)
            self.assertEqual('pkg:maven/example/sample@1.0?classifier=data', expected[0]['purl'])

            for annotation in (' (optional)', ' -- module example.sample [auto]',
                               ' (optional) -- module example.sample (auto)'):
                with self.subTest(annotation=annotation):
                    dependencies.write_text(line + annotation + '\n')
                    self.assertEqual(expected, maven_components(dependencies))

            dependencies.write_text(line + '\x1b[36m -- module example.sample [auto]\x1b[m\n')
            self.assertEqual(expected, maven_components(dependencies))
