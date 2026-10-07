import boto3, json
c = boto3.client("lambda", endpoint_url="http://localhost:4566", region_name="us-east-1",
                 aws_access_key_id="123456789012", aws_secret_access_key="test")
print("BASE", c.get_function_configuration(FunctionName="lab-reconcile")["FunctionArn"])
print("ALIAS", c.get_alias(FunctionName="lab-reconcile", Name="approved")["AliasArn"])
r = c.invoke(FunctionName="lab-reconcile", Qualifier="approved",
             InvocationType="RequestResponse", Payload=b'{"eventId":"diagnostic"}')
print("INVOKE", r["StatusCode"], r.get("FunctionError"), r["Payload"].read().decode())
