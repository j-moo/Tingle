"""Compare host C arithmetic against supplied, immutable synthetic PC handoff.

Inputs stay outside Git; generated binaries/reports go under iot/build/tests.
This is a host calculation comparison, never evidence of real radio or server ACK.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess

import profile_payload as codec

FLAGS = ('smoking', 'children', 'religion', 'tattoo', 'age')


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def same(actual, expected):
    if expected is None:
        assert actual is None, (actual, expected)
    else:
        assert isinstance(actual, (float, int)) and abs(actual - expected) <= 1e-8, (actual, expected)


def check(result, expected):
    assert result['calculated']
    for side, field in (('ab', 'Ab'), ('ba', 'Ba')):
        same(result[side], expected[side]['score'])
        same(result['coverage' + field], expected[side]['coverage'])
        assert result['excluded' + field] == sum(1 << FLAGS.index(x) for x in expected[side]['excluded'])
        assert result['unresolved' + field] == sum(1 << FLAGS.index(x) for x in expected[side]['unresolved'])
    same(result['harmonic'], expected.get('harmonic', expected.get('score')))
    assert result['match'] == expected.get('match', expected.get('eligible'))


def personal(snapshot):
    result = dict.fromkeys(codec.PERSONAL_FIELDS)
    result = {k: {'state': 'unknown'} for k in result}
    age = snapshot.pop('legacyAge', None)
    result['actualAge'] = {'state': 'value', 'value': age['age']} if age and age.get('age') is not None else {'state': 'unknown'}
    result['desiredAge'] = {'mode': 'range', 'min': age['min'], 'max': age['max']} if age and age.get('min') is not None and age.get('max') is not None else {'mode': 'unknown'}
    result['deviceOptions'] = dict.fromkeys(codec.OPTIONS, True)
    return result


def logical(person):
    answers = person['answers']

    def cell(v, present=True):
        if not present:
            return {'state': 'missing'}
        if v is None or v == 'unknown':
            return {'state': 'unknown'}
        if v == 'any':
            return {'state': 'any'}
        return {'state': 'value', 'value': v}

    actual = {}
    for key, ids, rev in (('E', (1, 6, 11, 16), (6, 16)), ('A', (2, 7, 12, 17), (7, 17)),
                          ('C', (3, 8, 13, 18), (8, 18)), ('I', (5, 10, 15, 20), (10, 15, 20))):
        values = [answers.get(str(i)) for i in ids]
        actual[key] = cell(25 * (sum(6 - v if i in rev else v for i, v in zip(ids, values)) / 4 - 1)) if all(type(v) is int and 1 <= v <= 5 for v in values) else {'state': 'unknown'}
    actual.update({f'q{i}': cell(answers.get(str(i)), str(i) in answers) for i in range(30, 61)})
    return {'surveyVersion': person['surveyVersion'], 'actual': actual,
            'wishes': {str(i): cell(answers.get(str(i)), str(i) in answers) for i in range(61, 96)},
            'priority': cell(answers.get('96'), '96' in answers), 'strict': cell(answers.get('97'), '97' in answers),
            'tolerance': cell(answers.get('98'), '98' in answers), 'legacyAge': person.get('ageProfile')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--handoff', type=Path, required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    root = args.handoff.resolve()
    frozen = read(root / 'reference-r2/source-manifest.json')
    for entry in frozen['files']:
        source = (root / 'reference-r2' / entry['local']).resolve()
        assert source.is_relative_to(root)
        assert hashlib.sha256(source.read_bytes()).hexdigest() == entry['sha256']
    core = read(root / 'golden-cases.json')
    rows = []

    def run(case_id, snapshots, expected):
        binaries = []
        for side in ('left', 'right'):
            snap = copy.deepcopy(snapshots[side])
            private = personal(snap)
            binaries.append(codec.encode(snap, private))
        paths = [args.output / 'left.bin', args.output / 'right.bin']
        for path, payload in zip(paths, binaries):
            path.write_bytes(payload)
        process = subprocess.run([str(args.runner.resolve()), '--score', *(str(p.resolve()) for p in paths)],
                                 capture_output=True, text=True, check=True)
        result = json.loads(process.stdout)
        check(result, expected)
        rows.append({'id': case_id, 'result': 'C_REFERENCE_MATCH', 'actual': result})

    for case in core['cases']:
        if case['id'] == 'MIXED_VERSIONS':
            # Altering survey metadata must fail decoding before any arithmetic.
            left = copy.deepcopy(case['logicalSnapshot']['left'])
            payload = bytearray(codec.encode(left, personal(left)))
            payload[8] ^= 1
            bad = args.output / 'bad-version.bin'; bad.write_bytes(payload)
            good = args.output / 'left.bin'
            observed = json.loads(subprocess.check_output([str(args.runner.resolve()), '--score', str(good.resolve()), str(bad.resolve())], text=True))
            assert not observed['calculated'] and not observed['match'] and observed['ab'] is None
            rows.append({'id': case['id'], 'result': 'UNSUPPORTED_VERSION_REJECTED'})
        else:
            run(case['id'], case['logicalSnapshot'], case['expectedDevice'])
    base = read(root / 'reference-r2/source/base27.json')['cases']
    all_cases = {c['id']: c for c in base}
    for extra in read(root / 'reference-r2/records/extra-cases.json'):
        c = copy.deepcopy(all_cases[extra['base']])
        c['id'] = extra['id']
        for side, changes in extra['changes'].items():
            c['input'][side]['answers'].update(changes)
        all_cases[c['id']] = c
    for entry in read(root / 'reference-r2/records/verified-results.json')['cases']:
        case = all_cases[entry['id']]
        if entry['legacy'] != 'MATCH_INDEPENDENT':
            rows.append({'id': entry['id'], 'result': 'LEGACY_SOURCE_SKIPPED'}); continue
        snapshots = {s: logical(case['input'][s]) for s in ('left', 'right')}
        if entry.get('invalidLegacyAnswerIds'):
            rejected = False
            for snap in copy.deepcopy(list(snapshots.values())):
                try:
                    codec.encode(snap, personal(snap))
                except ValueError:
                    rejected = True
            assert rejected, entry['id']
            rows.append({'id': entry['id'], 'result': 'INVALID_REFERENCE_INPUT_REJECTED'})
            continue
        if any(s['surveyVersion'] != codec.SURVEY for s in snapshots.values()):
            rows.append({'id': entry['id'], 'result': 'UNSUPPORTED_VERSION_REJECTED'}); continue
        if any(s['tolerance']['state'] != 'value' or s['strict']['state'] not in ('value', 'any') or
               s['priority']['state'] not in ('value', 'any') for s in snapshots.values()):
            rows.append({'id': entry['id'], 'result': 'UNRESOLVED_POLICY_INPUT_NO_DEFAULT'}); continue
        run('r2:' + entry['id'], snapshots, entry['reference'])
    report = {'scope': 'HOST_C_CALCULATION_ONLY', 'cases': rows, 'board': 'NOT_TESTED'}
    (args.output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'compared': sum(r['result'] == 'C_REFERENCE_MATCH' for r in rows),
                      'statuses': {x: sum(r['result'] == x for r in rows) for x in {r['result'] for r in rows}}}))


if __name__ == '__main__':
    main()
