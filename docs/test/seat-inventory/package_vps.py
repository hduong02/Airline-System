"""Package compiled test classes and JUnit only; production code comes from the VPS image."""
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
TARGET = ROOT / 'microservices/services/seat-service/target'
report = TARGET / 'surefire-reports/TEST-com.example.seat_service.inventory.SeatInventoryIntegrationTest.xml'
properties = {p.attrib['name']: p.attrib['value'] for p in ET.parse(report).findall('./properties/property')}
classpath = properties['java.class.path']
staging = TARGET / 'inventory-vps-package'
staging.mkdir(exist_ok=True)
subprocess.run(['javac', '-cp', classpath, '-d', str(staging), str(HERE / 'InventoryTestRunner.java')], check=True)
archive = TARGET / 'seat-inventory-tests.zip'
test_package = Path('com/example/seat_service/inventory')
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    output.write(staging / 'InventoryTestRunner.class', 'classes/InventoryTestRunner.class')
    for path in (TARGET / 'test-classes' / test_package).glob('SeatInventoryIntegrationTest*.class'):
        output.write(path, 'classes/' + (test_package / path.name).as_posix())
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.name.startswith(('junit-', 'opentest4j-', 'apiguardian-')) and path.suffix == '.jar':
            output.write(path, 'lib/' + path.name)
    output.write(HERE / 'remote_vps.py', 'remote_vps.py')
print(archive)
print(f'Package size: {archive.stat().st_size:,} bytes')
