import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('dependency_trust', Path(__file__).resolve().parents[1] / 'verify_dependency_trust.py')
trust = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trust)

class DependencyTrustTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / 'app').mkdir()
        (self.root / 'gradle').mkdir()
        (self.root / 'app/gradle.lockfile').write_text('sample:library:1.0=debugCompileClasspath\nempty=releaseRuntimeClasspath\n')
        self.metadata = self.root / 'gradle/verification-metadata.xml'
        self.metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>'
            '<components><component group="sample" name="library" version="1.0"><artifact name="library.jar">'
            '<sha256 value="' + 'a' * 64 + '"/></artifact></component></components></verification-metadata>')

    def tearDown(self):
        self.temporary.cleanup()

    def replace(self, before, after):
        self.metadata.write_text(self.metadata.read_text().replace(before, after))

    def testCompleteControlsAreAccepted(self):
        self.assertEqual({'lockedModules': 1, 'verifiedArtifacts': 1}, trust.inspect(self.root))

    def testMissingMetadataIsRejected(self):
        self.metadata.unlink()
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testMissingLockStateIsRejected(self):
        (self.root / 'app/gradle.lockfile').unlink()
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testUncheckedArtifactExemptionIsRejected(self):
        self.replace('</configuration>', '<trusted-artifacts><trust group="sample"/></trusted-artifacts></configuration>')
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testMalformedChecksumIsRejected(self):
        self.replace('a' * 64, 'bad-checksum')
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testMetadataVerificationCannotBeDisabled(self):
        self.replace('<verify-metadata>true', '<verify-metadata>false')
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testLockedModuleWithoutChecksumIsRejected(self):
        self.replace('version="1.0"', 'version="2.0"')
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testChangingLockVersionIsRejected(self):
        (self.root / 'app/gradle.lockfile').write_text('sample:library:1.0-SNAPSHOT=debugCompileClasspath\n')
        with self.assertRaises(ValueError): trust.inspect(self.root)

    def testDynamicLockVersionsAreRejected(self):
        for version in ('+', '1.+', '1.2+', 'latest.release', '[1.0,2.0)'):
            with self.subTest(version=version):
                (self.root / 'app/gradle.lockfile').write_text(
                    f'sample:library:{version}=debugCompileClasspath\n')
                with self.assertRaisesRegex(ValueError, 'changing or dynamic'):
                    trust.inspect(self.root)

    def testLenientPropertyIsRejected(self):
        (self.root / 'gradle.properties').write_text('org.gradle.dependency.verification=lenient\n')
        with self.assertRaises(ValueError): trust.inspect(self.root)

if __name__ == '__main__': unittest.main()
