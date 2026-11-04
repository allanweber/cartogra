package io.cartogra.web.security;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every user-facing write route a service maps, read from its handler mappings so a new endpoint is covered
 * without editing a list. Callers exclude service-to-service paths, which the gateway never proxies.
 */
public final class WriteRoutes {

    private static final Set<RequestMethod> WRITES =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private WriteRoutes() {}

    public record Route(HttpMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    public static List<Route> of(ApplicationContext context, String... excludedPrefixes) {
        var mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        return mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                    Stream<RequestMethod> writes = methods.isEmpty()
                            ? Stream.of(RequestMethod.POST)
                            : methods.stream().filter(WRITES::contains);
                    List<String> patterns = List.copyOf(info.getPatternValues());
                    return writes.flatMap(m -> patterns.stream()
                            .map(p -> new Route(HttpMethod.valueOf(m.name()), concrete(p))));
                })
                .filter(r -> Stream.of(excludedPrefixes).noneMatch(r.path()::startsWith))
                .filter(r -> !r.path().startsWith("/error"))
                .sorted(Comparator.comparing(Route::toString))
                .toList();
    }

    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }
}
