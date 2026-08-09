import BookList from "@/components/BookList";

interface HomeRecommendationsProps {
  initialRecommendations: Book[];
}

const HomeRecommendations = ({
  initialRecommendations,
}: HomeRecommendationsProps) => (
  <BookList
    title="Recommended for you"
    books={initialRecommendations}
    containerClassName="mt-12 sm:mt-20"
    showViewAllButton
  />
);

export default HomeRecommendations;
