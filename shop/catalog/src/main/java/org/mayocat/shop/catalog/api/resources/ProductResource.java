package org.mayocat.shop.catalog.api.resources;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.mayocat.accounts.model.Role;
import org.mayocat.authorization.annotation.Authorized;
import org.mayocat.model.AddonFieldType;
import org.mayocat.model.AddonSource;
import org.mayocat.rest.Resource;
import org.mayocat.image.model.Image;
import org.mayocat.image.model.Thumbnail;
import org.mayocat.image.store.ThumbnailStore;
import org.mayocat.model.Addon;
import org.mayocat.model.Attachment;
import org.mayocat.shop.catalog.CatalogService;
import org.mayocat.addons.api.representation.AddonRepresentation;
import org.mayocat.shop.catalog.api.representations.ProductRepresentation;
import org.mayocat.shop.catalog.model.Collection;
import org.mayocat.shop.catalog.model.Product;
import org.mayocat.rest.resources.AbstractAttachmentResource;
import org.mayocat.rest.annotation.ExistingTenant;
import org.mayocat.rest.representations.EntityReferenceRepresentation;
import org.mayocat.rest.representations.ImageRepresentation;
import org.mayocat.store.EntityAlreadyExistsException;
import org.mayocat.store.EntityDoesNotExistException;
import org.mayocat.store.InvalidEntityException;
import org.mayocat.store.InvalidMoveOperation;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataParam;
import com.yammer.metrics.annotation.Timed;

@Component("/api/1.0/product/")
@Path("/api/1.0/product/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ExistingTenant
public class ProductResource extends AbstractAttachmentResource implements Resource
{
    @Inject
    private CatalogService catalogService;

    @Inject
    private Provider<ThumbnailStore> thumbnailStore;

    @Inject
    private Logger logger;

    @GET
    @Timed
    @Authorized
    public List<ProductRepresentation> getProducts(
            @QueryParam("number") @DefaultValue("50") Integer number,
            @QueryParam("offset") @DefaultValue("0") Integer offset,
            @QueryParam("filter") @DefaultValue("") String filter)
    {
        if (filter.equals("uncategorized")) {
            return this.wrapInRepresentations(this.catalogService.findOrphanProducts());
        } else {
            return this.wrapInRepresentations(this.catalogService.findAllProducts(number, offset));
        }
    }

    @Path("{slug}")
    @GET
    @Timed
    @Authorized
    public Object getProduct(@PathParam("slug") String slug, @QueryParam("expand") @DefaultValue("") String expand)
    {
        Product product = this.catalogService.findProductBySlug(slug);
        if (product == null) {
            return Response.status(404).build();
        }
        List<String> expansions = Strings.isNullOrEmpty(expand)
                ? Collections.<String>emptyList()
                : Arrays.asList(expand.split(","));

        if (expansions.contains("collections")) {
            List<Collection> collections = this.catalogService.findCollectionsForProduct(product);
            if (expansions.contains("images")) {
                return this.wrapInRepresentation(product, collections, this.getImages(slug));
            } else {
                return this.wrapInRepresentation(product, collections, null);
            }
        } else if (expansions.contains("images")) {
            return this.wrapInRepresentation(product, this.getImages(slug));
        } else {
            return this.wrapInRepresentation(product);
        }
    }

    @Path("{slug}/image")
    @GET
    public List<ImageRepresentation> getImages(@PathParam("slug") String slug)
    {
        List<ImageRepresentation> result = new ArrayList();
        Product product = this.catalogService.findProductBySlug(slug);
        if (product == null) {
            throw new WebApplicationException(Response.status(404).build());
        }

        for (Attachment attachment : this.getAttachmentStore().findAllChildrenOf(product,
                Arrays.asList("png", "jpg", "jpeg", "gif")))
        {
            List<Thumbnail> thumbnails = thumbnailStore.get().findAll(attachment);
            Image image = new Image(attachment, thumbnails);
            ImageRepresentation representation = new ImageRepresentation(image);
            if (product.getFeaturedImageId() != null) {
                if (product.getFeaturedImageId().equals(attachment.getId())) {
                    representation.setFeatured(true);
                }
            }

            result.add(representation);
        }

        return result;
    }

    @Path("{slug}/attachment")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response addAttachment(@PathParam("slug") String slug,
            @FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @FormDataParam("title") String title, @FormDataParam("description") String description)
    {
        Product product = this.catalogService.findProductBySlug(slug);
        if (product == null) {
            return Response.status(404).build();
        }

        return this.addAttachment(uploadedInputStream, fileDetail.getFileName(), title, description,
                Optional.of(product.getId()));
    }

    @Path("{slug}/move")
    @POST
    @Timed
    @Authorized
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.WILDCARD)
    public Response move(@PathParam("slug") String slug,
            @FormParam("before") String slugOfProductToMoveBeforeOf,
            @FormParam("after") String slugOfProductToMoveAfterTo)
    {
        try {
            if (!Strings.isNullOrEmpty(slugOfProductToMoveAfterTo)) {
                this.catalogService.moveProduct(slug,
                        slugOfProductToMoveAfterTo, CatalogService.InsertPosition.AFTER);
            } else {
                this.catalogService.moveProduct(slug, slugOfProductToMoveBeforeOf);
            }

            return Response.noContent().build();
        } catch (InvalidMoveOperation e) {
            throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid move operation").type(MediaType.TEXT_PLAIN_TYPE).build());
        }
    }

    @Path("{slug}")
    @POST
    @Timed
    @Authorized
    // Partial update : NOT idempotent
    public Response updateProduct(@PathParam("slug") String slug,
            ProductRepresentation updatedProductRepresentation)
    {
        try {
            Product product = this.catalogService.findProductBySlug(slug);
            if (product == null) {
                return Response.status(404).build();
            } else {
                product.setId(product.getId());
                product.setTitle(updatedProductRepresentation.getTitle());
                product.setDescription(updatedProductRepresentation.getDescription());
                product.setModel(updatedProductRepresentation.getModel());
                product.setOnShelf(updatedProductRepresentation.getOnShelf());
                product.setPrice(updatedProductRepresentation.getPrice());
                product.setStock(updatedProductRepresentation.getStock());

                // Addons
                List<Addon> addons = Lists.newArrayList();
                for (AddonRepresentation addonRepresentation : updatedProductRepresentation.getAddons()) {
                    Addon addon = new Addon();
                    addon.setSource(AddonSource.fromJson(addonRepresentation.getSource()));
                    addon.setType(AddonFieldType.fromJson(addonRepresentation.getType()));
                    addon.setValue(addonRepresentation.getValue());
                    addon.setKey(addonRepresentation.getKey());
                    addon.setGroup(addonRepresentation.getGroup());
                    addons.add(addon);
                }

                product.setAddons(addons);

                // Featured image
                if (updatedProductRepresentation.getFeaturedImage() != null) {
                    ImageRepresentation representation = updatedProductRepresentation.getFeaturedImage();

                    Attachment featuredImage =
                            this.getAttachmentStore().findBySlugAndExtension(representation.getSlug(),
                                    representation.getFile().getExtension());
                    if (featuredImage != null) {
                        product.setFeaturedImageId(featuredImage.getId());
                    }
                }

                this.catalogService.updateProduct(product);
            }

            return Response.ok().build();
        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityDoesNotExistException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Path("{slug}")
    @DELETE
    @Authorized
    @Consumes(MediaType.WILDCARD)
    public Response deleteProduct(@PathParam("slug") String slug)
    {
        try {
            this.catalogService.deleteProduct(slug);

            return Response.noContent().build();
        }
        catch (EntityDoesNotExistException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No product with this slug could be found\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Path("{slug}")
    @PUT
    @Timed
    @Authorized
    public Response replaceProduct(@PathParam("slug") String slug, Product newProduct)
    {
        // TODO
        throw new RuntimeException("Not implemented");
    }

    @POST
    @Timed
    @Authorized(roles = { Role.ADMIN })
    public Response createProduct(Product product)
    {
        try {
            Product created = this.catalogService.createProduct(product);

            // Respond with a created URI relative to this API URL.
            // This will add a location header like http://host/api/<version>/product/my-created-product
            return Response.created(new URI(created.getSlug())).build();

        } catch (InvalidEntityException e) {
            throw new com.yammer.dropwizard.validation.InvalidEntityException(e.getMessage(), e.getErrors());
        } catch (EntityAlreadyExistsException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("A product with this slug already exists\n").type(MediaType.TEXT_PLAIN_TYPE).build();
        } catch (URISyntaxException e) {
            throw new WebApplicationException(e);
        }
    }

    // ////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private List<ProductRepresentation> wrapInRepresentations(List<Product> products)
    {
        List<ProductRepresentation> result = new ArrayList<ProductRepresentation>();
        for (Product product : products) {
            result.add(this.wrapInRepresentation(product));
        }
        return result;
    }

    private ProductRepresentation wrapInRepresentation(Product product)
    {
        ProductRepresentation pr = new ProductRepresentation(product);
        if (product.getAddons().isLoaded()) {
            List<AddonRepresentation> addons = Lists.newArrayList();
            for (Addon a : product.getAddons().get()) {
                addons.add(new AddonRepresentation(a));
            }
            pr.setAddons(addons);
        }
        return pr;
    }

    private ProductRepresentation wrapInRepresentation(Product product,
            List<ImageRepresentation> images)
    {
        ProductRepresentation result = new ProductRepresentation(product);
        if (images != null) {
            result.setImages(images);
            for (ImageRepresentation image: images) {
                if (image.isFeaturedImage()) {
                    result.setFeaturedImage(image);
                }
            }
        }
        if (product.getAddons().isLoaded()) {
            List<AddonRepresentation> addons = Lists.newArrayList();
            for (Addon a : product.getAddons().get()) {
                addons.add(new AddonRepresentation(a));
            }
            result.setAddons(addons);
        }
        return result;
    }

    private ProductRepresentation wrapInRepresentation(Product product, List<Collection> collections,
            List<ImageRepresentation> images)
    {
        List<EntityReferenceRepresentation> collectionsReferences = Lists.newArrayList();
        for (Collection collection : collections) {
            collectionsReferences
                    .add(new EntityReferenceRepresentation(collection.getTitle(), collection.getSlug(),
                            "/api/1.0/collection/" + collection.getSlug()));
        }
        ProductRepresentation result = new ProductRepresentation(product, collectionsReferences);
        if (images != null) {
            result.setImages(images);
            for (ImageRepresentation image: images) {
                if (image.isFeaturedImage()) {
                    result.setFeaturedImage(image);
                }
            }
        }
        if (product.getAddons().isLoaded()) {
            List<AddonRepresentation> addons = Lists.newArrayList();
            for (Addon a : product.getAddons().get()) {
                addons.add(new AddonRepresentation(a));
            }
            result.setAddons(addons);
        }
        return result;
    }
}
