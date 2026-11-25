"""Entrypoint ordering with real service directory locks and a controlled HTTP join."""
import signal
from unittest.mock import MagicMock, patch

from dremla_collection import server
from support import ServiceCase


class ServerLifecycleTests(ServiceCase):
    def run_main(self, on_join=None, ignore_hup=False):
        handlers = {signal.SIGTERM: signal.SIG_DFL,
                    signal.SIGHUP: signal.SIG_IGN if ignore_hup else signal.SIG_DFL}
        installed = []

        def install(sig, handler):
            previous = handlers[sig]
            handlers[sig] = handler
            installed.append((sig, handler))
            return previous

        http = MagicMock()
        http.__enter__.return_value = http
        http.server_port = 0
        # ThreadingHTTPServer.__exit__ calls server_close, which joins request threads.
        if on_join:
            http.__exit__.side_effect = lambda *args: on_join()
        with patch.object(server, "CollectionService", return_value=self.service), \
                patch.object(server, "CollectionHTTPServer", return_value=http), \
                patch.object(server.sys, "argv", ["collection-service"]), \
                patch.dict(server.os.environ, {"DREMLA_COLLECTION_PROCESSOR": "fixture",
                                               "DREMLA_COLLECTION_ACQUISITION": "fixture"}), \
                patch.object(server.signal, "signal", side_effect=install), \
                patch.object(server.signal, "getsignal", side_effect=handlers.get), \
                patch.object(server.time, "sleep", side_effect=lambda _: handlers[signal.SIGTERM](signal.SIGTERM, None)):
            server.main()
        return installed

    def test_directory_locks_are_held_until_http_request_threads_join(self):
        joined = []

        def join_requests():
            def competing_service():
                replacement = self.open_service(start_worker=False)
                replacement.close()
            self.assert_code("already-running", competing_service)
            joined.append(True)
            return False

        self.run_main(on_join=join_requests)
        self.assertEqual(joined, [True])
        replacement = self.open_service(start_worker=False)
        replacement.close()

    def test_inherited_ignored_sighup_is_not_overridden(self):
        installed = self.run_main(ignore_hup=True)
        self.assertFalse(any(sig == signal.SIGHUP and handler != signal.SIG_IGN
                             for sig, handler in installed))
