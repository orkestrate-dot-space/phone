package space.orkestrate.phone;

interface IOrkestrateDaemon {
  String call(String reqJson) = 1;
  void destroy() = 16777114;
}
