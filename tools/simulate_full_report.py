"""模拟智能桶满桶上报：密钥从 SMARTWASTE_DEVICE_TOKEN 环境变量读取。"""
import argparse
import json
import os
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--bin-id', required=True)
    parser.add_argument('--device-id', required=True)
    parser.add_argument('--request-id', default=None, help='重试时使用相同请求编号')
    args = parser.parse_args()
    token = os.environ.get('SMARTWASTE_DEVICE_TOKEN')
    if not token:
        parser.error('请先设置 SMARTWASTE_DEVICE_TOKEN 环境变量')
    request_id = args.request_id or str(uuid.uuid4())
    payload = json.dumps({'requestId': request_id}).encode('utf-8')
    request = urllib.request.Request(
        args.base_url.rstrip('/') + '/device/bins/' + args.bin_id + '/full-reports',
        data=payload, method='POST', headers={
            'Content-Type': 'application/json', 'X-Device-Id': args.device_id, 'X-Device-Token': token})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
        print(json.dumps({'requestId': request_id, 'result': result}, ensure_ascii=False))
        return 0 if result.get('code') == 1 else 1
    except (urllib.error.URLError, ValueError) as error:
        print(json.dumps({'requestId': request_id, 'error': str(error)}, ensure_ascii=False))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
