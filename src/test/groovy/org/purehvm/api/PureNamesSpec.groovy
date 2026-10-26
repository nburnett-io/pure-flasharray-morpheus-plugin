package org.purehvm.api

import spock.lang.Specification

class PureNamesSpec extends Specification {

    def 'name validation follows Purity rules'() {
        expect:
        PureNames.isValidName(name) == valid

        where:
        name               | valid
        'hvm-ds01'         | true
        'ds_01'            | true
        '123'              | false   // needs a letter
        '-ds'              | false
        'ds_'              | false
        'a' * 63           | true
        'a' * 64           | false
        'has space'        | false
    }

    def 'sanitize turns hostnames and cluster names into valid array names'() {
        expect:
        PureNames.sanitize(raw) == clean
        PureNames.isValidName(clean)

        where:
        raw                    | clean
        'HVM Cluster A'        | 'HVM-Cluster-A'
        'node_01.local'        | 'node-01-local'
        '--x--'                | 'x'
        '12345'                | 'h-12345'
    }

    def 'collision suffixes follow Pure: -iscsi then -iscsi-N'() {
        expect:
        PureNames.nonColliding('hvm01', []) == 'hvm01'
        PureNames.nonColliding('hvm01', ['HVM01']) == 'hvm01-iscsi'
        PureNames.nonColliding('hvm01', ['hvm01', 'hvm01-iscsi']) == 'hvm01-iscsi-1'
        PureNames.nonColliding('a' * 63, ['a' * 63]).length() <= 63
    }

    def 'WWID and device path round-trip'() {
        given:
        def serial = 'A1B2C3D4E5F6A7B8C9D0E1F2'

        expect:
        PureNames.wwid(serial) == '3624a9370a1b2c3d4e5f6a7b8c9d0e1f2'
        PureNames.devicePath(serial) == '/dev/mapper/3624a9370a1b2c3d4e5f6a7b8c9d0e1f2'
        PureNames.serialFromWwid('/dev/mapper/3624a9370a1b2c3d4e5f6a7b8c9d0e1f2') == serial
        PureNames.serialFromWwid('36000c29abcdef0123456789abcdef012') == null
    }

    def 'the tag namespace comes from the setting; blank or invalid means the default'() {
        expect:
        PureNames.configureTagNamespace(setting) == expected
        PureNames.tagNamespace == expected

        cleanup:
        PureNames.configureTagNamespace(null)

        where:
        setting                  | expected
        null                     | 'org.purehvm'
        '  '                     | 'org.purehvm'
        'morpheus-hvm.example'   | 'morpheus-hvm.example'
        ' spaced.ns '            | 'spaced.ns'
        'bad ns/with chars'      | 'org.purehvm'
        'x' * 65                 | 'org.purehvm'
    }

    def 'gfs2 fs name is lowercase and at most 30 chars'() {
        expect:
        PureNames.gfs2FsName('Restore of DS-01 2026/10/20 snapshot') == 'restore_of_ds-01_2026_10_20_sn'
        PureNames.gfs2FsName('Restore of DS-01 2026/10/20 snapshot').length() <= 30
    }
}
