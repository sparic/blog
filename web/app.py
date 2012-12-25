import web
import time
import os
import subprocess
import json

BLOG_EXTENSION = ".blog"
USER_STORE = "static/user_query/"
DEFAULT_GRAPH = "static/images/BerkeleyLogo.png"
EXAMPLE_BLOG_PATH = 'example.blog'

urls = ('/', 'blog_web_ui')
render = web.template.render('templates/')

app = web.application(urls, globals())

with open(EXAMPLE_BLOG_PATH) as f:
  example_blog_code = f.read()

def run_process(script_name):
    command = ["./run.sh", "--displaycbn", script_name]
    p = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    output = p.communicate()[0]
    returncode = p.returncode
    return output, returncode

def store_script(prefix, script):
    #Write input into local file
    script_name = prefix+BLOG_EXTENSION
    input_handler = open(script_name, 'w')
    input_handler.write(script)
    input_handler.close()
    return script_name

def execute_script(script):
    #Define name of the script
    current = str(time.time())
    filename = "tmp_%s" % current

    prefix = USER_STORE + filename

    script_name = store_script(prefix, script)
    output, returncode = run_process(script_name)

    return output

class blog_web_ui:
    def GET(self):
        return render.index(example_blog_code, "Your result will appear here.")

    def POST(self):
        s = web.input().textfield
        result = execute_script(s)
        return result

if __name__ == '__main__':
    app.run()
