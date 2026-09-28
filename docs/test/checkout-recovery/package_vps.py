"""Package test bytecode and test libraries; application code comes from VPS images."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
TARGET = ROOT / 'microservices/services/booking-service/target'
STAGING = TARGET / 'checkout-recovery-package'
STAGING.mkdir(exist_ok=True)
archive = TARGET / 'checkout-recovery-tests.zip'
selected = {
    'booking': ['service.impl.BookingReservationRecoveryTest', 'service.impl.BookingFlowIntegrationTest',
                'service.BookingExpiryJobTest', 'service.BookingExpiryServiceTest'],
    'payment': ['service.impl.PaymentInitiationIntegrationTest', 'service.impl.PaymentExpiryReconciliationTest',
                'service.impl.PaymentExpiryConcurrencyTest'],
}
manifest = {'files': {}, 'suites': selected}
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    def add(path, name):
        output.write(path, name)
        manifest['files'][name] = hashlib.sha256(path.read_bytes()).hexdigest()

    for service, classes in selected.items():
        target = ROOT / ('microservices/services/' + service + '-service/target')
        report_class = 'BookingReservationRecoveryTest' if service == 'booking' else 'PaymentInitiationIntegrationTest'
        report = target / ('surefire-reports/TEST-com.example.' + service + '_service.service.impl.' + report_class + '.xml')
        properties = {p.attrib['name']: p.attrib['value'] for p in ET.parse(report).findall('./properties/property')}
        classpath = properties['java.class.path']
        subprocess.run(['javac', '-cp', classpath, '-d', str(STAGING), str(HERE / 'RecoveryTestRunner.java')], check=True)
        add(STAGING / 'RecoveryTestRunner.class', service + '/classes/RecoveryTestRunner.class')
        for name in classes:
            relative = Path('com/example/' + service + '_service/' + name.replace('.', '/'))
            for path in (target / 'test-classes' / relative.parent).glob(relative.name + '*.class'):
                add(path, service + '/classes/' + (relative.parent / path.name).as_posix())
        for entry in classpath.split(os.pathsep):
            path = Path(entry)
            if path.suffix == '.jar' and path.name.startswith(('junit-', 'opentest4j-', 'apiguardian-',
                    'mockito-', 'byte-buddy-', 'objenesis-', 'spring-test-', 'spring-boot-test-')):
                add(path, service + '/lib/' + path.name)
    add(HERE / 'remote_vps.py', 'remote_vps.py')
    output.writestr('manifest.json', json.dumps(manifest, indent=2) + '\n')
print(str(archive))
print('Package bytes:', archive.stat().st_size)
