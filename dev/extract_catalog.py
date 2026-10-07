"""Extract only the unconditional handler dictionary of BlenderMCPServer._execute_command_internal."""
import ast
import sys

module = ast.parse(open(sys.argv[1], encoding='utf-8').read())
server = next(node for node in module.body if isinstance(node, ast.ClassDef) and node.name == 'BlenderMCPServer')
dispatch = next(node for node in server.body if isinstance(node, ast.FunctionDef) and node.name == '_execute_command_internal')
handlers = next(node.value for node in dispatch.body if isinstance(node, ast.Assign)
                and any(isinstance(name, ast.Name) and name.id == 'handlers' for name in node.targets))
if not isinstance(handlers, ast.Dict):
    raise ValueError('base handlers not a dictionary literal')
ids = [ast.literal_eval(key) for key in handlers.keys]
if not all(isinstance(key, str) for key in ids) or len(ids) != len(set(ids)):
    raise ValueError('nonliteral or duplicate handler')
print('[' + ' '.join('{:id "' + key + '"}' for key in ids) + ']')
