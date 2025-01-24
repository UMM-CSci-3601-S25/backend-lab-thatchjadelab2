package umm3601.todo;

import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.mongojack.JacksonMongoCollection;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Filters.and;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;
import umm3601.Controller;

public class TodoController implements Controller {

    private static final String API_TODOS = "/api/todos";
    private static final String API_TODOS_BY_ID = "/api/todos/{id}";
    static final String OWNER_KEY = "owner";
    static final String STATUS_KEY = "status";
    static final String BODY_KEY = "body";
    static final String CATEGORY_KEY = "category";

    private final JacksonMongoCollection<Todo> todoCollection;

    public TodoController(MongoDatabase database) {
        todoCollection = JacksonMongoCollection.builder().build(
            database,
            "todos",
            Todo.class,
            UuidRepresentation.STANDARD);
    }

    public void getTodo(Context ctx) {
        String id = ctx.pathParam("id");
        Todo todo;

        try {
            todo = todoCollection.find(eq("_id", new ObjectId(id))).first();
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse("The requested todo id wasn't a legal Mongo Object ID.");
        }
        if (todo == null) {
            throw new NotFoundResponse("The requested todo was not found");
        } else {
            ctx.json(todo);
            ctx.status(HttpStatus.OK);
        }
    }

    public void getTodos(Context ctx) {
        Bson combinedFilter = constructFilter(ctx);
        Bson sortingOrder = constructSortingOrder(ctx);

        ArrayList<Todo> matchingTodos = todoCollection
        .find(combinedFilter)
        .sort(sortingOrder)
        .into(new ArrayList<>());

        // Set the JSON body of the response to be the list of todos returned by the database.
        // According to the Javalin documentation (https://javalin.io/documentation#context),
        // this calls result(jsonString), and also sets content type to json
        ctx.json(matchingTodos);

        // Explicitly set the context status to OK
        ctx.status(HttpStatus.OK);
    }

    private Bson constructFilter(Context ctx) {
        List<Bson> filters = new ArrayList<>(); // start with an empty list of filters

        if (ctx.queryParamMap().containsKey(OWNER_KEY)) {
            Pattern pattern = Pattern.compile(Pattern.quote(ctx.queryParam(OWNER_KEY)), Pattern.CASE_INSENSITIVE);
            filters.add(regex(OWNER_KEY, pattern));
        }
        if (ctx.queryParamMap().containsKey(BODY_KEY)) {
            Pattern pattern = Pattern.compile(Pattern.quote(ctx.queryParam(BODY_KEY)), Pattern.CASE_INSENSITIVE);
            filters.add(regex(BODY_KEY, pattern));
        }
        if (ctx.queryParamMap().containsKey(CATEGORY_KEY)) {
            Pattern pattern = Pattern.compile(Pattern.quote(ctx.queryParam(CATEGORY_KEY)), Pattern.CASE_INSENSITIVE);
            filters.add(regex(CATEGORY_KEY, pattern));
        }

        // Combine the list of filters into a single filtering document.
        Bson combinedFilter = filters.isEmpty() ? new Document() : and(filters);

        return combinedFilter;
    }

    private Bson constructSortingOrder(Context ctx) {
        // Sort the results. Use the `sortby` query param (default "name")
        // as the field to sort by, and the query param `sortorder` (default
        // "asc") to specify the sort order.
        String sortBy = Objects.requireNonNullElse(ctx.queryParam("sortby"), "category");
        String sortOrder = Objects.requireNonNullElse(ctx.queryParam("category"), "asc");
        Bson sortingOrder = sortOrder.equals("desc") ?  Sorts.descending(sortBy) : Sorts.ascending(sortBy);
        return sortingOrder;
    }

        /**
     * Add a new todo using information from the context
     * (as long as the information gives "legal" values to User fields)
     *
     * @param ctx a Javalin HTTP context that provides the todo info
     *  in the JSON body of the request
     */
    public void addNewTodo(Context ctx) {
        /*
        * The follow chain of statements uses the Javalin validator system
        * to verify that instance of `User` provided in this context is
        * a "legal" todo. It checks the following things (in order):
        *    - The todo has a value for the name (`usr.name != null`)
        *    - The todo name is not blank (`usr.name.length > 0`)
        *    - The provided email is valid (matches EMAIL_REGEX)
        *    - The provided age is > 0
        *    - The provided age is < REASONABLE_AGE_LIMIT
        *    - The provided role is valid (one of "admin", "editor", or "viewer")
        *    - A non-blank company is provided
        * If any of these checks fail, the Javalin system will throw a
        * `BadRequestResponse` with an appropriate error message.
        */
        String body = ctx.body();
        Todo newTodo = ctx.bodyValidator(Todo.class)
        .check(todo -> todo.owner != null && todo.owner.length() > 0,
            "Todo must have a non-empty owner; body was " + body)
        .check(todo -> todo.body != null && todo.body.length() > 0,
            "Todo must have a non-empty body; body was " + body)
        .check(todo -> todo.category != null && todo.category.length() > 0,
            "Todo must have a non-empty category; body was " + body)
        .check(todo -> todo.status != null,
            "Todo must have a valid boolean status; body was " + body)
        .get();

        // Add the new todo to the database
        todoCollection.insertOne(newTodo);

        // Set the JSON response to be the `_id` of the newly created todo.
        // This gives the client the opportunity to know the ID of the new todo,
        // which it can then use to perform further operations (e.g., a GET request
        // to get and display the details of the new todo).
        ctx.json(Map.of("id", newTodo._id));
        // 201 (`HttpStatus.CREATED`) is the HTTP code for when we successfully
        // create a new resource (a todo in this case).
        // See, e.g., https://developer.mozilla.org/en-US/docs/Web/HTTP/Status
        // for a description of the various response codes.
        ctx.status(HttpStatus.CREATED);
    }

    /**
     * Delete the todo specified by the `id` parameter in the request.
     *
     * @param ctx a Javalin HTTP context
     */
    public void deleteTodo(Context ctx) {
        String id = ctx.pathParam("id");
        DeleteResult deleteResult = todoCollection.deleteOne(eq("_id", new ObjectId(id)));
        // We should have deleted 1 or 0 todos, depending on whether `id` is a valid todo ID.
        if (deleteResult.getDeletedCount() != 1) {
        ctx.status(HttpStatus.NOT_FOUND);
        throw new NotFoundResponse(
            "Was unable to delete ID "
            + id
            + "; perhaps illegal ID or an ID for an item not in the system?");
        }
        ctx.status(HttpStatus.OK);
    }

        /**
     * Setup routes for the `todo` collection endpoints.
     *
     * These endpoints are:
     *   - `GET /api/todos/:id`
     *       - Get the specified todo
     *   - `GET /api/todos?age=NUMBER&company=STRING&name=STRING`
     *      - List todos, filtered using query parameters
     *      - `age`, `company`, and `name` are optional query parameters
     *   - `GET /api/todosByCompany`
     *     - Get todo names and IDs, possibly filtered, grouped by company
     *   - `DELETE /api/todos/:id`
     *      - Delete the specified todo
     *   - `POST /api/todos`
     *      - Create a new todo
     *      - The todo info is in the JSON body of the HTTP request
     *
     * GROUPS SHOULD CREATE THEIR OWN CONTROLLERS THAT IMPLEMENT THE
     * `Controller` INTERFACE FOR WHATEVER DATA THEY'RE WORKING WITH.
     * You'll then implement the `addRoutes` method for that controller,
     * which will set up the routes for that data. The `Server#setupRoutes`
     * method will then call `addRoutes` for each controller, which will
     * add the routes for that controller's data.
     *
     * @param server The Javalin server instance
     * @param todoController The controller that handles the todo endpoints
     */
    public void addRoutes(Javalin server) {
        // Get the specified todo
        server.get(API_TODOS_BY_ID, this::getTodo);

        // List todos, filtered using query parameters
        server.get(API_TODOS, this::getTodos);

        // Get the todos, possibly filtered, grouped by company
        //server.get("/api/todosByCompany", this::getUsersGroupedByCompany);

        // Add new todo with the todo info being in the JSON body
        // of the HTTP request
        server.post(API_TODOS, this::addNewTodo);

        // Delete the specified todo
        server.delete(API_TODOS_BY_ID, this::deleteTodo);
    }
}
