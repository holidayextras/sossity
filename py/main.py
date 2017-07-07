import json
from glob import glob
import networkx as nx
from networkx.drawing.nx_agraph import graphviz_layout

node_types = ["sources", "pipelines", "sinks"]
default_force_bucket_destroy = False
default_bucket_location = "EU"
default_machine_type = "n1-standard-1"
default_cluster_permissions = [
    "https://www.googleapis.com/auth/compute",
    "https://www.googleapis.com/auth/devstorage.read_only",
    "https://www.googleapis.com/auth/logging.write",
    "https://www.googleapis.com/auth/monitoring",
    "https://www.googleapis.com/auth/cloud-platform"
]


def config_check(config):
    return True


def create_graph(config):
    graph = nx.DiGraph()
    map(lambda x: graph.add_node(x), [node for types in map(lambda types: config[types], node_types) for node in types])
    for source, edges in config["edges"].items():
        for node in edges:
            graph.add_edge(source, node)
    nx.drawing.nx_pydot.write_dot(graph, 'test.dot')
    return graph


def get_providers(config):
    providers = ["google", "googlecli", "googleappengine", "googlebigquery"]
    _ = {}
    for provider in providers:
        _.update({provider: {"credentials": config["provider"]["credentials"],
                             "project": config["provider"]["project"],
                             "region": config["provider"]["zone"]}})
    return _


def get_pubsub_topic(edges):
    topics = {}
    for topic in edges.keys():
        topics.update({topic: {"name": topic}})
    return topics


def get_pubsub_subscription(edges):
    subs = {}
    for topic, subscriptions in edges.items():
        for sub in subscriptions:
            sub_name = f"{topic}-sub-{sub}"
            subs.update({sub_name: {
                "name": sub_name,
                "topic": topic,
                "depends_on": [f"google_pubsub_topic.{topic}"]}
            })
    return subs


def get_storage_bucket(config):
    # TODO check the force destroy and new bucket opts
    project_name = config['provider']['project']
    name = f"{project_name}-data"
    buckets = {
        name: {
            "name": name,
            "force_destroy": default_force_bucket_destroy,
            "location": default_bucket_location
        }
    }
    return buckets


def setup_container(cluster_info, zone):
    perms = default_cluster_permissions.copy()
    if cluster_info["node_config"].get("oauth_scopes"):
        [perms.append(i) for i in cluster_info["node_config"].get("oauth_scopes") if i not in perms]
    return {
        "name": cluster_info["name"],
        "initial_node_count": cluster_info["initial_node_count"],
        "zone": zone,
        "master_auth": cluster_info["master_auth"],
        "node_config": {
            "oauth_scopes": perms,
            "machine_type": cluster_info["node_config"]["machine_type"] if cluster_info["node_config"].get("machine_type") else default_machine_type}
    }


def get_container_clusters(config):
    return {cluster["name"]: setup_container(cluster, config["provider"]["zone"]) for cluster in config["clusters"]}


def get_appengine_app(config):


def create_terraform_dict(configdata):
    graph = create_graph(configdata)
    output = {
        "provider": get_providers(configdata),
        "google_pubsub_topic": get_pubsub_topic(configdata["edges"]),
        "google_pubsub_subscription": get_pubsub_subscription(configdata["edges"]),
        "google_storage_bucket": get_storage_bucket(configdata),
        "google_container_cluster": get_container_clusters(configdata),
        "googleappengine_app": get_appengine_app(configdata)
    }
    print(output['googleappengine_app'])
    exit()


def main():
    configdata = {}
    for file in glob('py/config/*.json'):
        with open(file) as f:
            configdata.update(json.loads(f.read()))
    # print(configdata)
    if not config_check(configdata):
        print('bad things happened')
        exit()
    create_terraform_dict(configdata)


if __name__ == '__main__':
    main()
