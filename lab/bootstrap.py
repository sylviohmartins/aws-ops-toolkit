"""Provision ONLY the loopback Moto emulator; dummy credentials never contact AWS."""
import boto3
import io
import json
import os
import zipfile

endpoint = 'http://localhost:5000'
def client(service):
    return boto3.client(service, endpoint_url=endpoint, region_name='us-east-1',
                        aws_access_key_id='testing', aws_secret_access_key='testing')

sts = client('sts')
print(json.dumps(sts.get_caller_identity(), default=str))
ddb = client('dynamodb')
if 'lab-payments' not in ddb.list_tables()['TableNames']:
    ddb.create_table(TableName='lab-payments', KeySchema=[{'AttributeName':'id','KeyType':'HASH'}],
                     AttributeDefinitions=[{'AttributeName':'id','AttributeType':'S'}], BillingMode='PAY_PER_REQUEST')
if 'lab-consumed' not in ddb.list_tables()['TableNames']:
    ddb.create_table(TableName='lab-consumed', KeySchema=[{'AttributeName':'id','KeyType':'HASH'}],
                     AttributeDefinitions=[{'AttributeName':'id','AttributeType':'S'}], BillingMode='PAY_PER_REQUEST')
for i in range(int(os.environ.get('LAB_RECORDS', '200'))):
    ddb.put_item(TableName='lab-payments', Item={'id':{'S':f'payment-{i:06d}'},'status':{'S':'PENDING'},'version':{'N':'1'}})
sqs = client('sqs')
queues = {}
for name in ['lab-events', 'lab-dlq', 'lab-replay', 'lab-sns']:
    queues[name] = sqs.create_queue(QueueName=name)['QueueUrl']
for i in range(10):
    sqs.send_message(QueueUrl=queues['lab-dlq'], MessageBody=json.dumps({'eventId':f'event-{i}', 'paymentId':f'payment-{i:06d}'}))
sns=client('sns')
topic=sns.create_topic(Name='lab-events')['TopicArn']
queue_arn=sqs.get_queue_attributes(QueueUrl=queues['lab-sns'], AttributeNames=['QueueArn'])['Attributes']['QueueArn']
sns.subscribe(TopicArn=topic, Protocol='sqs', Endpoint=queue_arn)
s3=client('s3')
for name in ['lab-evidence', 'lab-archive']:
    if name not in [b['Name'] for b in s3.list_buckets()['Buckets']]:
        s3.create_bucket(Bucket=name)
    s3.put_bucket_versioning(Bucket=name,VersioningConfiguration={'Status':'Enabled'})
s3.put_object(Bucket='lab-evidence', Key='input/evidence.txt', Body=b'synthetic-evidence\n')
iam=client('iam')
try:
    role=iam.create_role(RoleName='lab-lambda',AssumeRolePolicyDocument=json.dumps({'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'Service':'lambda.amazonaws.com'},'Action':'sts:AssumeRole'}]}))['Role']['Arn']
except iam.exceptions.EntityAlreadyExistsException:
    role=iam.get_role(RoleName='lab-lambda')['Role']['Arn']
code=io.BytesIO()
with zipfile.ZipFile(code,'w') as archive:
    archive.writestr('handler.py', 'def run(event, context):\n    if event.get("fail"): raise ValueError("synthetic failure")\n    return {"accepted": True, "eventId": event.get("eventId")}\n')
lam=client('lambda')
if 'lab-reconcile' not in [f['FunctionName'] for f in lam.list_functions()['Functions']]:
    lam.create_function(FunctionName='lab-reconcile', Runtime='python3.11', Role=role, Handler='handler.run', Code={'ZipFile':code.getvalue()},Timeout=10,Publish=True)
    lam.create_alias(FunctionName='lab-reconcile', Name='approved', FunctionVersion='1')
print(json.dumps({'queues':queues,'topic':topic,'table':'lab-payments'}))
